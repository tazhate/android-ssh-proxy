package com.example.sshproxy.ui.settings

import kotlinx.coroutines.flow.first

import android.content.Intent
import android.app.Activity
import android.widget.EditText
import android.content.Context
import com.example.sshproxy.data.Server
import com.example.sshproxy.data.SshKey
import com.example.sshproxy.R
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.util.Base64
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.sshproxy.data.KeyRepository
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.data.SshKeyManager
import com.example.sshproxy.databinding.FragmentSettingsBinding
import com.example.sshproxy.ui.log.LogFragment
import com.example.sshproxy.ui.setup.ServerInstructionsDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.activity.result.contract.ActivityResultContracts

class SettingsFragment : Fragment() {

    private lateinit var serverRepository: com.example.sshproxy.data.ServerRepository
    private lateinit var keyRepository: com.example.sshproxy.data.KeyRepository

    private fun doImportBackup(json: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val gson = Gson()
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                val map = gson.fromJson<Map<String, Any>>(json, mapType)
                val serversJson = gson.toJson(map["servers"])
                val keysJson = gson.toJson(map["keys"])
                val serversType = object : TypeToken<List<Server>>() {}.type
                val servers: List<Server> = gson.fromJson(serversJson, serversType)

                // keysJson is a list of maps with meta/encryptedPem/pemPassword
                val keysListType = object : TypeToken<List<Map<String, Any?>>>() {}.type
                val keysWithPem: List<Map<String, Any?>> = gson.fromJson(keysJson, keysListType)
                // val keyManager = SshKeyManager(requireContext(), keyRepository) // Не используется
                val existingServers = serverRepository.getAllServers().first()
                val existingKeys = keyRepository.getAllKeys().first()
                val newServers = servers.filter { s -> existingServers.none { it.id == s.id } }
                val newKeys = mutableListOf<SshKey>()

                for (keyMap in keysWithPem) {
                    val metaObj = keyMap["meta"]
                    val encryptedPem = keyMap["encryptedPem"] as? String
                    val pemPassword = keyMap["pemPassword"] as? String
                    // Deserialize meta
                    val metaJson = gson.toJson(metaObj)
                    val keyMeta = gson.fromJson(metaJson, SshKey::class.java)
                    if (existingKeys.none { it.id == keyMeta.id }) {
                        newKeys.add(keyMeta)
                    }
                    // Restore PEM if present
                    if (encryptedPem != null && pemPassword != null) {
                        val pem = com.example.sshproxy.security.PemAesUtil.decryptPem(encryptedPem, pemPassword)
                        // Генерируем новый пароль, шифруем PEM и сохраняем с паролем в Keystore
                        val keyManagerLocal = SshKeyManager(requireContext(), keyRepository)
                        keyManagerLocal.saveEncryptedPem(keyMeta.id, pem)
                    }
                    // Восстановить файл публичного ключа, если отсутствует
                    val publicKeyFile = java.io.File(requireContext().filesDir, "ssh_public_${keyMeta.id}")
                    if (!publicKeyFile.exists() && keyMeta.publicKey.isNotEmpty()) {
                        try {
                            // publicKey в SshKey — это строка вида "ssh-ed25519 AAAAC3..."
                            val parts = keyMeta.publicKey.split(" ")
                            if (parts.size >= 2) {
                                val pubKeyBytes = android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT)
                                publicKeyFile.writeBytes(pubKeyBytes)
                            }
                        } catch (_: Exception) {}
                    }
                }

                newServers.forEach { serverRepository.insertServer(it) }
                newKeys.forEach { keyRepository.insertKey(it) }
                Toast.makeText(requireContext(), R.string.backup_import_success, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.backup_import_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun getAllServersAndKeysJson(): String {
        val servers = serverRepository.getAllServers().first()
        val keys = keyRepository.getAllKeys().first()
        val keyManager = SshKeyManager(requireContext(), keyRepository)
        val keysWithPem = keys.map { key ->
            val keyId = key.id
            val privateKey = keyManager.getPrivateKey(keyId)
            val pem = if (privateKey != null) keyManager.convertPrivateKeyToPem(privateKey) else null
            val password = if (pem != null) com.example.sshproxy.security.PemAesUtil.generatePassword() else null
            val encryptedPem = if (pem != null && password != null) com.example.sshproxy.security.PemAesUtil.encryptPem(pem, password) else null
            mapOf(
                "meta" to key,
                "encryptedPem" to encryptedPem,
                "pemPassword" to password
            )
        }
        val backup = mapOf(
            "servers" to servers,
            "keys" to keysWithPem
        )
        return Gson().toJson(backup)
    }

    private fun showImportDialog() {
        val options = arrayOf(
            getString(R.string.backup_import_file),
            getString(R.string.backup_import_text)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_import_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importBackupFromFile()
                    1 -> importBackupFromText()
                }
            }
            .show()
    }

    private fun importBackupFromFile() {
        importBackupLauncher.launch(arrayOf("application/json"))
    }

    private fun importBackupFromText() {
        val input = EditText(requireContext())
        input.hint = getString(R.string.backup_import_text_hint)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_import_text_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val base64 = input.text.toString().trim()
                if (base64.isNotEmpty()) {
                    try {
                        val json = decodeBackupFromBase64(base64)
                        doImportBackup(json)
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), R.string.backup_import_error, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val REQUEST_CODE_IMPORT_FILE = 1002

    private fun showBackupExportDialog() {
        val options = arrayOf(
            getString(R.string.backup_export_file),
            getString(R.string.backup_export_text)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_export_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportBackupToFile()
                    1 -> exportBackupAsText()
                }
            }
            .show()
    }

    private val exportBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            requireContext().contentResolver.openOutputStream(it)?.use { out ->
                pendingExportJson?.let { json ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
            }
            Toast.makeText(requireContext(), R.string.backup_export_success, Toast.LENGTH_SHORT).show()
        }
        pendingExportJson = null
    }

    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                val json = requireContext().contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                    reader.readText()
                }
                json?.let { doImportBackup(it) }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.backup_import_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportBackupToFile() {
        viewLifecycleOwner.lifecycleScope.launch {
            val json = getAllServersAndKeysJson()
            pendingExportJson = json
            val fileName = "sshproxy-backup-${System.currentTimeMillis()}.json"
            exportBackupLauncher.launch(fileName)
        }
    }

    private fun exportBackupAsText() {
        viewLifecycleOwner.lifecycleScope.launch {
            val json = getAllServersAndKeysJson()
            val base64 = encodeBackupToBase64(json)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.backup_export_text_title)
                .setMessage(base64)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.backup_export_copy) { _, _ ->
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Backup", base64))
                    Toast.makeText(requireContext(), R.string.backup_export_copied, Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private var pendingExportJson: String? = null
    private val REQUEST_CODE_EXPORT_FILE = 1001
    // --- Backup/Import helpers ---
    private fun encodeBackupToBase64(json: String): String {
        return Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun decodeBackupFromBase64(base64: String): String {
        return String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun parseBackupJson(json: String): Pair<List<Server>, List<SshKey>> {
        val gson = Gson()
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        val map = gson.fromJson<Map<String, Any>>(json, mapType)
        val serversJson = gson.toJson(map["servers"])
        val keysJson = gson.toJson(map["keys"])
        val serversType = object : TypeToken<List<Server>>() {}.type
        val keysType = object : TypeToken<List<SshKey>>() {}.type
        val servers: List<Server> = gson.fromJson(serversJson, serversType)
        val keys: List<SshKey> = gson.fromJson(keysJson, keysType)
        return Pair(servers, keys)
    }
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var preferencesManager: PreferencesManager
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
    _binding = FragmentSettingsBinding.inflate(inflater, container, false)
    serverRepository = com.example.sshproxy.data.ServerRepository(requireContext())
    keyRepository = com.example.sshproxy.data.KeyRepository(requireContext())
    return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        preferencesManager = PreferencesManager(requireContext())
        
        loadSettings()
        setupListeners()
        setupThemeSpinner()
        setupLanguageSpinner()

        // Автоматическая генерация ключа при первом запуске
        viewLifecycleOwner.lifecycleScope.launch {
            val keyManager = SshKeyManager(requireContext(), keyRepository)
            val hasKey = keyRepository.getAllKeys().first().isNotEmpty()
            if (!hasKey) {
                try {
                    keyManager.generateKeyPair("default")
                    android.util.Log.d("SettingsFragment", "SSH key generated automatically on first launch")
                } catch (e: Exception) {
                    android.util.Log.e("SettingsFragment", "Failed to generate SSH key: ${e.message}", e)
                }
            }
        }
    }
    
    private fun loadSettings() {
        binding.apply {
            switchAutoReconnect.isChecked = preferencesManager.isAutoReconnectEnabled()
            editHealthCheckInterval.setText((preferencesManager.getHealthCheckInterval() / 1000).toString())
            editMaxReconnectAttempts.setText(preferencesManager.getMaxReconnectAttempts().toString())
            editInitialBackoff.setText((preferencesManager.getInitialBackoffMs() / 1000).toString())
            editMaxBackoff.setText((preferencesManager.getMaxBackoffMs() / 1000).toString())
            editBackoffMultiplier.setText(preferencesManager.getBackoffMultiplier().toString())
        }
    }
    
    private fun setupListeners() {
        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }

        binding.btnViewLog.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(com.example.sshproxy.R.id.fragmentContainer, LogFragment())
                .addToBackStack("settings")
                .commit()
        }

        binding.btnBackup.setOnClickListener {
            android.util.Log.d("SettingsFragment", "Backup button clicked")
            showBackupExportDialog()
        }

        binding.btnImport.setOnClickListener {
            android.util.Log.d("SettingsFragment", "Import button clicked")
            showImportDialog()
        }

        binding.btnSetup.setOnClickListener {
            showServerSetupInstructions()
        }
    }
    
    private fun showServerSetupInstructions() {
        lifecycleScope.launch {
            try {
                val keyManager = SshKeyManager(requireContext(), KeyRepository(requireContext()))
                val publicKey = keyManager.getActivePublicKey()
                
                if (publicKey.isNotEmpty()) {
                    ServerInstructionsDialog.newInstance(publicKey)
                        .show(parentFragmentManager, "server_instructions")
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(com.example.sshproxy.R.string.no_ssh_key))
                        .setMessage(getString(com.example.sshproxy.R.string.please_generate_ssh_key))
                        .setPositiveButton(getString(com.example.sshproxy.R.string.ok), null)
                        .show()
                }
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(com.example.sshproxy.R.string.error))
                    .setMessage(getString(com.example.sshproxy.R.string.failed_to_get_ssh_key, e.message))
                    .setPositiveButton(getString(com.example.sshproxy.R.string.ok), null)
                    .show()
            }
        }
    }
    
    private fun saveSettings() {
        try {
            binding.apply {
                preferencesManager.setAutoReconnectEnabled(switchAutoReconnect.isChecked)
                
                val healthCheckInterval = editHealthCheckInterval.text.toString().toLongOrNull() ?: 30
                preferencesManager.setHealthCheckInterval(healthCheckInterval * 1000)
                
                val maxAttempts = editMaxReconnectAttempts.text.toString().toIntOrNull() ?: 10
                preferencesManager.setMaxReconnectAttempts(maxAttempts)
                
                val initialBackoff = editInitialBackoff.text.toString().toLongOrNull() ?: 1
                preferencesManager.setInitialBackoffMs(initialBackoff * 1000)
                
                val maxBackoff = editMaxBackoff.text.toString().toLongOrNull() ?: 300
                preferencesManager.setMaxBackoffMs(maxBackoff * 1000)

                val backoffMultiplier = editBackoffMultiplier.text.toString().toFloatOrNull() ?: 2.0f
                preferencesManager.setBackoffMultiplier(backoffMultiplier)
            }
            
            Toast.makeText(context, getString(com.example.sshproxy.R.string.settings_saved), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, getString(com.example.sshproxy.R.string.error_saving_settings, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun setupLanguageSpinner() {
        val languages = resources.getStringArray(com.example.sshproxy.R.array.language_entries)
        val languageValues = resources.getStringArray(com.example.sshproxy.R.array.language_values)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLanguage.adapter = adapter

        val currentLanguage = preferencesManager.getLanguage()
        val currentLanguageIndex = languageValues.indexOf(currentLanguage)
        if (currentLanguageIndex != -1) {
            binding.spinnerLanguage.setSelection(currentLanguageIndex)
        }

        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedLanguage = languageValues[position]
                preferencesManager.setLanguage(selectedLanguage)
                applyLanguage(selectedLanguage)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun applyLanguage(language: String) {
        val localeList = if (language == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    private fun setupThemeSpinner() {
        val themes = resources.getStringArray(com.example.sshproxy.R.array.theme_entries)
        val themeValues = resources.getStringArray(com.example.sshproxy.R.array.theme_values)
        
        // Добавим отладочный лог
        android.util.Log.d("SettingsFragment", "Available themes: ${themes.joinToString()}")
        android.util.Log.d("SettingsFragment", "Available theme values: ${themeValues.joinToString()}")
        
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, themes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTheme.adapter = adapter

        val currentTheme = preferencesManager.getTheme()
        android.util.Log.d("SettingsFragment", "Current theme from preferences: $currentTheme")
        
        val currentThemeIndex = themeValues.indexOf(currentTheme)
        if (currentThemeIndex != -1) {
            binding.spinnerTheme.setSelection(currentThemeIndex)
        }

        var isInitialLoad = true
        binding.spinnerTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Игнорируем первый вызов при загрузке
                if (isInitialLoad) {
                    isInitialLoad = false
                    return
                }
                
                val selectedTheme = themeValues[position]
                android.util.Log.d("SettingsFragment", "Theme selected: $selectedTheme, current: ${preferencesManager.getTheme()}")
                
                if (selectedTheme != preferencesManager.getTheme()) {
                    preferencesManager.setTheme(selectedTheme)
                    android.util.Log.d("SettingsFragment", "Theme saved to preferences: $selectedTheme")
                    
                    // Применяем тему немедленно
                    when (selectedTheme) {
                        "light" -> {
                            android.util.Log.d("SettingsFragment", "Applying light theme")
                            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                        }
                        "dark" -> {
                            android.util.Log.d("SettingsFragment", "Applying dark theme")
                            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                        }
                        else -> {
                            android.util.Log.d("SettingsFragment", "Applying system theme")
                            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                        }
                    }
                    
                    // Показываем сообщение и перезапускаем активити
                    if (isAdded && !isDetached && activity != null) {
                        Toast.makeText(context, "Theme changed to $selectedTheme", Toast.LENGTH_SHORT).show()
                        
                        // Перезапускаем активити
                        android.util.Log.d("SettingsFragment", "Recreating activity")
                        requireActivity().recreate()
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
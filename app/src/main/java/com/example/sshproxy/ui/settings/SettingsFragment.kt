package com.example.sshproxy.ui.settings

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

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var preferencesManager: PreferencesManager
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        preferencesManager = PreferencesManager(requireContext())
        
        loadSettings()
        setupListeners()
        setupThemeSpinner()
        setupLanguageSpinner()
    }
    
    private fun loadSettings() {
        binding.apply {
            switchAutoReconnect.isChecked = preferencesManager.isAutoReconnectEnabled()
            editHealthCheckInterval.setText((preferencesManager.getHealthCheckInterval() / 1000).toString())
            editMaxReconnectAttempts.setText(preferencesManager.getMaxReconnectAttempts().toString())
            editInitialBackoff.setText((preferencesManager.getInitialBackoffMs() / 1000).toString())
            editMaxBackoff.setText((preferencesManager.getMaxBackoffMs() / 1000).toString())
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
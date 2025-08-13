package com.example.sshproxy.ui.servers

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.sshproxy.data.KeyRepository
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.data.Server
import com.example.sshproxy.data.SshKey
import com.example.sshproxy.databinding.DialogAddServerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class AddServerDialog(
    private val server: Server? = null,
    private val onSave: (Server) -> Unit
) : DialogFragment() {
    
    private var selectedKey: SshKey? = null
    private var keys: List<SshKey> = emptyList()
    private lateinit var keyRepository: KeyRepository
    private lateinit var preferencesManager: PreferencesManager

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogAddServerBinding.inflate(layoutInflater)
        
        // Initialize repositories
        keyRepository = KeyRepository(requireContext())
        preferencesManager = PreferencesManager(requireContext())
        
        // Setup initial values
        server?.let {
            binding.etHost.setText(it.host)
            binding.etName.setText(it.name)
            binding.etUser.setText(it.username)
            binding.etPort.setText(it.port.toString())
        } ?: run {
            binding.etUser.setText("user")
            binding.etPort.setText("22")
        }
        
        // Load SSH keys
        loadSshKeys(binding)
        
        // SSH key selection
        binding.btnSelectKey.setOnClickListener {
            showSshKeySelectionDialog()
        }
        
        // Auto-fill name from host with URL cleanup
        binding.etHost.doAfterTextChanged { text ->
            if (!text.isNullOrEmpty()) {
                val cleanHost = cleanUrl(text.toString())
                
                // Update host field if URL was cleaned
                if (cleanHost != text.toString()) {
                    binding.etHost.setText(cleanHost)
                    binding.etHost.setSelection(cleanHost.length) // Move cursor to end
                }
                
                // Auto-fill name only if it's empty
                if (binding.etName.text.isNullOrEmpty()) {
                    binding.etName.setText(cleanHost)
                }
            }
        }
        
        // Toggle advanced options
        binding.btnToggleAdvanced.setOnClickListener {
            val isVisible = binding.advancedOptionsLayout.visibility == View.VISIBLE
            binding.advancedOptionsLayout.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.btnToggleAdvanced.setIconResource(
                if (isVisible) com.example.sshproxy.R.drawable.ic_expand_more 
                else com.example.sshproxy.R.drawable.ic_expand_less
            )
        }
        
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (server == null) "Add Server" else "Edit Server")
            .setView(binding.root)
            .setPositiveButton("Save") { _, _ ->
                val rawHost = binding.etHost.text.toString().trim()
                val cleanHost = cleanUrl(rawHost)
                val nameText = binding.etName.text?.toString()?.trim()
                val name = if (!nameText.isNullOrEmpty()) nameText else cleanHost
                val user = binding.etUser.text?.toString()?.trim() ?: "user"
                val port = binding.etPort.text?.toString()?.toIntOrNull() ?: 22
                
                if (cleanHost.isNotEmpty()) {
                    val newServer = Server(
                        id = server?.id ?: 0L,
                        name = name,
                        host = cleanHost,
                        port = port,
                        username = user,
                        sshKeyId = selectedKey?.id
                    )
                    onSave(newServer)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
    
    private fun loadSshKeys(binding: DialogAddServerBinding) {
        lifecycleScope.launch {
            keyRepository.getAllKeys().collect { keyList ->
                keys = keyList
                
                // Set initial selection based on server or use active key
                if (server?.sshKeyId != null) {
                    selectedKey = keys.find { it.id == server.sshKeyId }
                } else {
                    val activeKeyId = preferencesManager.getActiveKeyId()
                    selectedKey = keys.find { it.id == activeKeyId }
                }
                
                updateKeyDisplay(binding)
            }
        }
    }
    
    private fun updateKeyDisplay(binding: DialogAddServerBinding) {
        binding.tvSelectedKey.text = if (selectedKey != null) {
            selectedKey!!.name
        } else {
            "Use active key"
        }
    }
    
    private fun showSshKeySelectionDialog() {
        val keyNames = arrayOf("Use active key") + keys.map { it.name }.toTypedArray()
        val currentSelection = if (selectedKey != null) {
            keys.indexOfFirst { it.id == selectedKey!!.id } + 1
        } else {
            0
        }
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select SSH Key")
            .setSingleChoiceItems(keyNames, currentSelection) { dialog, which ->
                selectedKey = if (which == 0) null else keys[which - 1]
                dialog.dismiss()
                val binding = DialogAddServerBinding.bind(requireDialog().findViewById(android.R.id.content))
                updateKeyDisplay(binding)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun cleanUrl(input: String): String {
        var cleaned = input.trim()
        
        // Remove common URL prefixes
        val prefixes = listOf("http://", "https://", "ftp://", "ftps://")
        for (prefix in prefixes) {
            if (cleaned.startsWith(prefix, ignoreCase = true)) {
                cleaned = cleaned.substring(prefix.length)
                break
            }
        }
        
        // Remove trailing slashes and paths
        val slashIndex = cleaned.indexOf('/')
        if (slashIndex != -1) {
            cleaned = cleaned.substring(0, slashIndex)
        }
        
        // Remove port from display (user can set it separately)
        val colonIndex = cleaned.lastIndexOf(':')
        if (colonIndex != -1) {
            // Check if what follows the colon is a number (port)
            val afterColon = cleaned.substring(colonIndex + 1)
            if (afterColon.toIntOrNull() != null) {
                cleaned = cleaned.substring(0, colonIndex)
            }
        }
        
        return cleaned
    }
}

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
    private lateinit var binding: DialogAddServerBinding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogAddServerBinding.inflate(layoutInflater)
        
        // Initialize repositories
        keyRepository = KeyRepository(requireContext())
        preferencesManager = PreferencesManager(requireContext())
        
        // Setup initial values
        server?.let {
            binding.etHost.setText(it.host)
            binding.etServerName.setText(it.name)
            binding.etUsername.setText(it.username)
            binding.etPort.setText(it.port.toString())
            binding.etHttpProxyPort.setText(it.httpProxyPort.toString())
        } ?: run {
            binding.etUsername.setText("user")
            binding.etPort.setText("22")
            binding.etHttpProxyPort.setText("8080")
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
                if (binding.etServerName.text.isNullOrEmpty()) {
                    binding.etServerName.setText(cleanHost)
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
        
        val title = if (server == null) getString(com.example.sshproxy.R.string.add_server) else getString(com.example.sshproxy.R.string.edit_server)

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(binding.root)
            .setPositiveButton(getString(com.example.sshproxy.R.string.save)) { _, _ ->
                val name = binding.etServerName.text.toString().trim()
                val host = binding.etHost.text.toString().trim()
                val port = binding.etPort.text.toString().toIntOrNull() ?: 22
                val httpProxyPort = binding.etHttpProxyPort.text.toString().toIntOrNull() ?: 8080
                val username = binding.etUsername.text.toString().trim()

                if (name.isNotEmpty() && host.isNotEmpty() && username.isNotEmpty()) {
                    val newServer = server?.copy(
                        name = name,
                        host = host,
                        port = port,
                        httpProxyPort = httpProxyPort,
                        username = username
                    ) ?: Server(
                        name = name,
                        host = host,
                        port = port,
                        httpProxyPort = httpProxyPort,
                        username = username
                    )
                    onSave(newServer)
                }
            }
            .setNegativeButton(getString(com.example.sshproxy.R.string.cancel), null)
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
                updateKeyDisplay(binding)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun updateKeyDisplay(binding: DialogAddServerBinding) {
        binding.tvSelectedKey.text = if (selectedKey != null) {
            selectedKey!!.name
        } else {
            "Use active key"
        }
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

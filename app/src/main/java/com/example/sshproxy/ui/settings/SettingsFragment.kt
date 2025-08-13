package com.example.sshproxy.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
                        .setTitle("No SSH Key")
                        .setMessage("Please generate an SSH key first in the Keys tab before setting up a server.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Error")
                    .setMessage("Failed to get SSH key: ${e.message}")
                    .setPositiveButton("OK", null)
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
            
            Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Error saving settings: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

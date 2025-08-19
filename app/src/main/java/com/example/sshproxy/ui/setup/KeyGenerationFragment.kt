package com.example.sshproxy.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.sshproxy.MainActivity
import com.example.sshproxy.R
import com.example.sshproxy.data.KeyRepository
import com.example.sshproxy.data.SshKeyManager
import com.example.sshproxy.databinding.FragmentKeyGenerationBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class KeyGenerationFragment : Fragment() {
    private var _binding: FragmentKeyGenerationBinding? = null
    private val binding get() = _binding!!
    private var publicKey: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentKeyGenerationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
    checkOrGenerateKey()
        
        binding.btnCopyKey.setOnClickListener {
            copyPublicKey()
        }
        
        binding.btnServerSetup.setOnClickListener {
            showServerSetupInstructions()
        }
        
        binding.btnNext.setOnClickListener {
            (activity as? MainActivity)?.navigateToAddServer()
        }
    }
    
    private fun checkOrGenerateKey() {
        lifecycleScope.launch {
            val keyRepository = KeyRepository(requireContext())
            val keyManager = SshKeyManager(requireContext(), keyRepository)
            val preferencesManager = com.example.sshproxy.data.PreferencesManager(requireContext())
            val allKeys = keyRepository.getAllKeys().first()
            if (allKeys.isNotEmpty()) {
                // Если есть хотя бы один ключ, не генерируем новый, просто используем первый
                val existingKey = allKeys.first()
                publicKey = existingKey.publicKey
                binding.tvPublicKey.text = publicKey
                preferencesManager.setActiveKeyId(existingKey.id)
            } else {
                try {
                    val generatedKey = keyManager.generateKeyPair("Default Key")
                    publicKey = generatedKey.publicKey
                    binding.tvPublicKey.text = publicKey
                    preferencesManager.setActiveKeyId(generatedKey.id)
                } catch (e: Exception) {
                    binding.tvPublicKey.text = getString(com.example.sshproxy.R.string.error_generating_key_with_message, e.message)
                    Toast.makeText(context, getString(com.example.sshproxy.R.string.failed_to_generate_ssh_key), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun copyPublicKey() {
        if (publicKey.isNotEmpty()) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.ssh_public_key), publicKey)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, getString(R.string.public_key_copied), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showServerSetupInstructions() {
        if (publicKey.isNotEmpty()) {
            ServerInstructionsDialog.newInstance(publicKey)
                .show(parentFragmentManager, "server_instructions")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
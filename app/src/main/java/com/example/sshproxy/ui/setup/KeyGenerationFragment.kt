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
import com.example.sshproxy.data.KeyRepository
import com.example.sshproxy.data.SshKeyManager
import com.example.sshproxy.databinding.FragmentKeyGenerationBinding
import kotlinx.coroutines.launch

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
        
        generateKey()
        
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
    
    private fun generateKey() {
        lifecycleScope.launch {
            try {
                val keyManager = SshKeyManager(requireContext(), KeyRepository(requireContext()))
                val generatedKey = keyManager.generateKeyPair("Default Key")
                publicKey = generatedKey.publicKey
                binding.tvPublicKey.text = publicKey
            } catch (e: Exception) {
                binding.tvPublicKey.text = "Error generating key: ${e.message}"
                Toast.makeText(context, "Failed to generate SSH key", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun copyPublicKey() {
        if (publicKey.isNotEmpty()) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SSH Public Key", publicKey)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Public key copied to clipboard", Toast.LENGTH_SHORT).show()
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
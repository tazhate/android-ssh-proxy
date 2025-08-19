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
import com.example.sshproxy.data.KeyRepository
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.data.SshKey
import com.example.sshproxy.data.SshKeyManager
import com.example.sshproxy.databinding.FragmentKeySetupBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class KeySetupFragment : Fragment() {
    private var _binding: FragmentKeySetupBinding? = null
    private val binding get() = _binding!!
    private lateinit var keyManager: SshKeyManager
    private lateinit var preferencesManager: PreferencesManager
    private var currentKey: SshKey? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentKeySetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val keyRepository = KeyRepository(requireContext())
        keyManager = SshKeyManager(requireContext(), keyRepository)
        preferencesManager = PreferencesManager(requireContext())

        lifecycleScope.launch {
            val allKeys = keyRepository.getAllKeys().first()
            if (allKeys.isNotEmpty()) {
                // Если есть хотя бы один ключ, не генерируем новый, просто используем первый
                currentKey = allKeys.firstOrNull()
                // Можно выставить активный, если нужно
                currentKey?.let { preferencesManager.setActiveKeyId(it.id) }
            } else {
                // Только если ключей нет вообще, генерируем новый
                currentKey = keyManager.generateKeyPair("Default Key")
                currentKey?.let { preferencesManager.setActiveKeyId(it.id) }
            }

            if (currentKey != null) {
                binding.tvPublicKey.text = currentKey!!.publicKey
            } else {
                binding.tvPublicKey.text = "Error: Could not load or generate a key."
                binding.btnNext.isEnabled = false
            }
        }
        
        binding.btnCopyKey.setOnClickListener {
            copyKeyToClipboard()
        }
        
        binding.btnServerInstructions.setOnClickListener {
            showServerInstructions()
        }
        
        binding.btnNext.setOnClickListener {
            val fragment = ServerSetupFragment()
            parentFragmentManager.beginTransaction()
                .replace(com.example.sshproxy.R.id.fragmentContainer, fragment)
                .addToBackStack(null)
                .commit()
        }
    }

    private fun copyKeyToClipboard() {
        currentKey?.let {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SSH Public Key", it.publicKey)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Key copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showServerInstructions() {
        currentKey?.let {
            ServerInstructionsDialog.newInstance(it.publicKey).show(parentFragmentManager, "instructions")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

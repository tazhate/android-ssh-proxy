package com.example.sshproxy.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.sshproxy.MainActivity
import com.example.sshproxy.R
import com.example.sshproxy.data.Server
import com.example.sshproxy.data.ServerRepository
import com.example.sshproxy.databinding.FragmentAddFirstServerBinding
import kotlinx.coroutines.launch

class AddFirstServerFragment : Fragment() {
    private var _binding: FragmentAddFirstServerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAddFirstServerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupUI()
    }
    
    private fun setupUI() {
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
        
        binding.btnAddServer.setOnClickListener {
            addServer()
        }
        
        binding.btnSkip.setOnClickListener {
            completeSetup()
        }
    }
    
    private fun addServer() {
        val rawHost = binding.etHost.text.toString().trim()
        val cleanHost = cleanUrl(rawHost)
        val nameText = binding.etName.text?.toString()?.trim()
        val name = if (!nameText.isNullOrEmpty()) nameText else cleanHost
        val user = binding.etUser.text?.toString()?.trim() ?: "user"
        val port = binding.etPort.text?.toString()?.toIntOrNull() ?: 22
        
        if (cleanHost.isEmpty()) {
            Toast.makeText(context, "Please enter a server host", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                val serverRepository = ServerRepository(requireContext())
                val server = Server(
                    name = name,
                    host = cleanHost,
                    port = port,
                    username = user
                )
                serverRepository.insertServer(server)
                Toast.makeText(context, getString(com.example.sshproxy.R.string.server_added), Toast.LENGTH_SHORT).show()
                (activity as? MainActivity)?.completeSetup()
            } catch (e: Exception) {
                Toast.makeText(context, getString(com.example.sshproxy.R.string.error_adding_server, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun completeSetup() {
        (activity as? MainActivity)?.completeSetup()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

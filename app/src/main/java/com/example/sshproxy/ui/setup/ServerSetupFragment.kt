package com.example.sshproxy.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.example.sshproxy.data.Server
import com.example.sshproxy.data.ServerRepository
import com.example.sshproxy.databinding.FragmentServerSetupBinding
import com.example.sshproxy.MainActivity

class ServerSetupFragment : Fragment() {
    private var _binding: FragmentServerSetupBinding? = null
    private val binding get() = _binding!!
    private lateinit var serverRepository: ServerRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentServerSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        serverRepository = ServerRepository(requireContext())
        
        // Set defaults
        binding.etUser.setText("user")
        binding.etPort.setText("22")
        
        // Auto-fill name from host
        binding.etHost.doAfterTextChanged { text ->
            if (binding.etName.text.isNullOrEmpty() && !text.isNullOrEmpty()) {
                binding.etName.setText(text.toString())
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
        
        binding.btnSave.setOnClickListener {
            saveServerAndFinish()
        }
        
        binding.btnSkip.setOnClickListener {
            finishSetup()
        }
    }

    private fun saveServerAndFinish() {
        val host = binding.etHost.text.toString().trim()
        
        if (host.isEmpty()) {
            Toast.makeText(context, "Please enter a host", Toast.LENGTH_SHORT).show()
            return
        }
        
        val name = binding.etName.text?.toString()?.trim().takeIf { it?.isNotEmpty() == true } ?: host
        val user = binding.etUser.text?.toString()?.trim() ?: "user"
        val port = binding.etPort.text?.toString()?.toIntOrNull() ?: 22
        
        val server = Server(
            name = name,
            host = host,
            port = port,
            user = user
        )
        
        serverRepository.addServer(server)
        Toast.makeText(context, "Server added", Toast.LENGTH_SHORT).show()
        finishSetup()
    }

    private fun finishSetup() {
        val setupManager = SetupManager(activity as MainActivity)
        setupManager.completeSetup()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

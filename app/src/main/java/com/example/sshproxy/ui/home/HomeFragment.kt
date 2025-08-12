package com.example.sshproxy.ui.home

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.sshproxy.SshProxyService
import com.example.sshproxy.data.ServerRepository
import com.example.sshproxy.data.SshKeyManager
import com.example.sshproxy.databinding.FragmentHomeBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var serverRepository: ServerRepository
    private lateinit var keyManager: SshKeyManager
    private var selectedServerId: String? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == androidx.appcompat.app.AppCompatActivity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(context, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        serverRepository = ServerRepository(requireContext())
        keyManager = SshKeyManager(requireContext())
        
        setupUI()
        observeConnectionStatus()
        updateServerDisplay()
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            if (SshProxyService.isRunning.value == true) {
                stopVpnService()
            } else {
                checkAndConnect()
            }
        }

        binding.tvSelectedServer.setOnClickListener {
            showServerSelector()
        }
    }

    private fun observeConnectionStatus() {
        SshProxyService.isRunning.observe(viewLifecycleOwner) { isRunning ->
            binding.btnConnect.isSelected = isRunning
            binding.tvConnectionStatus.text = if (isRunning) "Connected" else "Disconnected"
            
            // Update button appearance
            if (isRunning) {
                binding.btnConnect.setIconResource(com.example.sshproxy.R.drawable.ic_stop)
            } else {
                binding.btnConnect.setIconResource(com.example.sshproxy.R.drawable.ic_power)
            }
        }
    }

    private fun updateServerDisplay() {
        val servers = serverRepository.getServers()
        val activeServer = servers.find { it.id == selectedServerId } ?: servers.firstOrNull()
        
        selectedServerId = activeServer?.id
        binding.tvSelectedServer.text = activeServer?.name ?: "No server selected"
    }

    private fun showServerSelector() {
        val servers = serverRepository.getServers()
        if (servers.isEmpty()) {
            Toast.makeText(context, "No servers configured", Toast.LENGTH_SHORT).show()
            return
        }

        val serverNames = servers.map { it.name }.toTypedArray()
        val currentIndex = servers.indexOfFirst { it.id == selectedServerId }.takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Server")
            .setSingleChoiceItems(serverNames, currentIndex) { dialog, which ->
                selectedServerId = servers[which].id
                updateServerDisplay()
                dialog.dismiss()
            }
            .show()
    }

    private fun checkAndConnect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(context, "Requires Android 10+", Toast.LENGTH_LONG).show()
            return
        }

        if (!keyManager.hasKeyPair()) {
            Toast.makeText(context, "No SSH key configured", Toast.LENGTH_SHORT).show()
            return
        }

        val server = serverRepository.getServers().find { it.id == selectedServerId }
        if (server == null) {
            Toast.makeText(context, "Please select a server", Toast.LENGTH_SHORT).show()
            return
        }

        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val server = serverRepository.getServers().find { it.id == selectedServerId } ?: return
        
        val intent = Intent(context, SshProxyService::class.java).apply {
            putExtra("HOST", server.host)
            putExtra("PORT", server.port)
            putExtra("USER", server.user)
        }
        requireContext().startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(context, SshProxyService::class.java).apply {
            action = "STOP_PROXY"
        }
        requireContext().startService(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

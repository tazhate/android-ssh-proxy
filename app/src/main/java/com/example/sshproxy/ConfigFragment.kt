package com.example.sshproxy

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.sshproxy.databinding.FragmentConfigBinding

class ConfigFragment : Fragment() {

    private var _binding: FragmentConfigBinding? = null
    private val binding get() = _binding!!

    private lateinit var configRepository: ConfigRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configRepository = ConfigRepository(requireContext())

        loadConfig()

        binding.toggleButton.setOnClickListener {
            if (binding.toggleButton.text == "Connect") {
                saveConfig()
                startVpn()
            } else {
                disconnectVpn()
            }
        }
    }

    private fun loadConfig() {
        configRepository.loadLatestConfig { config ->
            if (config != null) {
                binding.sshDetailsInput.setText(config.sshDetails)
                binding.proxyInput.setText(config.proxyInput)
                binding.payloadInput.setText(config.payload)
                binding.splitDelayInput.setText(config.splitDelay.toString())
                binding.dnsPrimaryInput.setText(config.dnsServer)
                binding.enableCompressionCheck.isChecked = config.enableCompression
                binding.mtuInput.setText(config.mtu.toString())
                binding.sendBufferInput.setText(config.sendBuffer.toString())
                binding.receiveBufferInput.setText(config.receiveBuffer.toString())
                binding.pingUrlInput.setText(config.pingUrl)
                binding.pingIntervalInput.setText(config.pingInterval.toString())
                binding.pingTimeoutInput.setText(config.pingTimeout.toString())
                binding.alwaysReconnectCheck.isChecked = config.alwaysReconnect
                binding.followRedirectsCheck.isChecked = config.followRedirects
                binding.enhancedToggle.isChecked = config.enhanced   // <-- NEW
            }
        }
    }

    private fun saveConfig() {
        configRepository.saveConfig(
            sshDetails = binding.sshDetailsInput.text.toString(),
            proxyInput = binding.proxyInput.text.toString(),
            payload = binding.payloadInput.text.toString(),
            splitDelay = binding.splitDelayInput.text.toString().toIntOrNull() ?: 500,
            dnsServer = binding.dnsPrimaryInput.text.toString(),
            pingTarget = "1.1.1.1",
            enableCompression = binding.enableCompressionCheck.isChecked,
            mtu = binding.mtuInput.text.toString().toIntOrNull() ?: 1500,
            sendBuffer = binding.sendBufferInput.text.toString().toIntOrNull() ?: 16384,
            receiveBuffer = binding.receiveBufferInput.text.toString().toIntOrNull() ?: 32768,
            pingUrl = binding.pingUrlInput.text.toString(),
            pingInterval = binding.pingIntervalInput.text.toString().toIntOrNull() ?: 2000,
            pingTimeout = binding.pingTimeoutInput.text.toString().toIntOrNull() ?: 5000,
            alwaysReconnect = binding.alwaysReconnectCheck.isChecked,
            followRedirects = binding.followRedirectsCheck.isChecked,
            enhanced = binding.enhancedToggle.isChecked   // <-- NEW
        )
    }

    private fun startVpn() {
        val intent = Intent(requireContext(), CustomVpnService::class.java).apply {
            action = CustomVpnService.ACTION_CONNECT
            putExtra("sshHost", binding.sshDetailsInput.text.toString())
            putExtra("sshPort", "80")
            val sshParts = binding.sshDetailsInput.text.toString().split("@")
            val credentials = sshParts.getOrNull(1)?.split(":") ?: listOf()
            putExtra("sshUser", credentials.getOrNull(0) ?: "")
            putExtra("sshPass", credentials.getOrNull(1) ?: "")
            val proxyParts = binding.proxyInput.text.toString().split(":")
            putExtra("proxyHost", proxyParts.getOrNull(0) ?: "")
            putExtra("proxyPort", proxyParts.getOrNull(1) ?: "80")
            putExtra("payload", binding.payloadInput.text.toString())
            putExtra("splitDelay", binding.splitDelayInput.text.toString().toIntOrNull() ?: 500)
            putExtra("dnsPrimary", binding.dnsPrimaryInput.text.toString())
            putExtra("dnsSecondary", "1.0.0.1")
            putExtra("enableCompression", binding.enableCompressionCheck.isChecked)
            putExtra("alwaysReconnect", binding.alwaysReconnectCheck.isChecked)
            putExtra("followRedirects", binding.followRedirectsCheck.isChecked)
            putExtra("usePayload", true)
            putExtra("proxySsl", false)
            putExtra("mtu", binding.mtuInput.text.toString().toIntOrNull() ?: 1500)
            putExtra("sendBuffer", binding.sendBufferInput.text.toString().toIntOrNull() ?: 16384)
            putExtra("receiveBuffer", binding.receiveBufferInput.text.toString().toIntOrNull() ?: 32768)
            putExtra("pingUrl", binding.pingUrlInput.text.toString())
            putExtra("pingInterval", binding.pingIntervalInput.text.toString().toIntOrNull() ?: 2000)
            putExtra("pingTimeout", binding.pingTimeoutInput.text.toString().toIntOrNull() ?: 10000)
            putExtra("enhanced", binding.enhancedToggle.isChecked)   // <-- NEW
        }
        requireContext().startService(intent)
        binding.toggleButton.text = "Disconnect"
        Toast.makeText(requireContext(), "Connecting...", Toast.LENGTH_SHORT).show()
    }

    private fun disconnectVpn() {
        val intent = Intent(requireContext(), CustomVpnService::class.java).apply {
            action = CustomVpnService.ACTION_DISCONNECT
        }
        requireContext().startService(intent)
        binding.toggleButton.text = "Connect"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
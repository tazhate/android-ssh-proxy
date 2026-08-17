package com.example.sshproxy

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.sshproxy.databinding.FragmentConfigBinding
import com.example.sshproxy.data.ConfigDatabase
import com.example.sshproxy.data.ConfigEntity
import com.example.sshproxy.data.ConfigRepository
import kotlinx.coroutines.launch

class ConfigFragment : Fragment() {

    private var _binding: FragmentConfigBinding? = null
    private val binding get() = _binding!!

    private lateinit var configRepository: ConfigRepository

    // Pre-built presets
    data class Preset(
        val name: String,
        val ssh: String,
        val proxy: String,
        val payload: String,
        val enhanced: Boolean = true
    )

    private val presets = listOf(
        Preset(
            name = "Safaricom 1 (odi.site)",
            ssh = "ssh.ethiodragon.sbs:80@f4r_72547b1ccb:n%ss7v%yfYBCr4X4J$",
            proxy = "odi.site:80",
            payload = "GET /cdn-cgi/trace HTTP/1.1[crlf]Host: [proxy][crlf][crlf][split]UNLOCK /? HTTP/1.1[crlf]Host: [host][crlf]Connection: upgrade[crlf]User-Agent: [ua][crlf]Upgrade: websocket[crlf][crlf][split]UNLOCK /? HTTP/1.1[crlf]Host: [proxy][crlf]Content-Length:999999999999[crlf]",
            enhanced = true
        ),
        Preset(
            name = "Airtel 1 (viton.com)",
            ssh = "ssh.ethiodragon.sbs:80@f4r_72547b1ccb:n%ss7v%yfYBCr4X4J$",
            proxy = "viton.com:80",
            payload = "GET /cdn-cgi/trace HTTP/1.1[crlf]Host: [rotate=apptest.airtel.ug.sg4.bonds.id;firebaseremoteconfig.googleapis.com;airtelcareapp.airtelkenya.com;h.facebook.com;device-provisioning.googleapis.com;www-cloudflaer.speedtest.net][crlf]User-Agent: [ua][crlf]Referer: [https/host][crlf][crlf][split]UNLOCK /? HTTP/1.1[crlf]Host: [host][crlf]Connection: upgrade[crlf]User-Agent: [ua][crlf]Upgrade: websocket[crlf][crlf][split]UNLOCK /? HTTP/1.1[crlf]Host: [proxy][crlf]Content-Length:999999999999[crlf]",
            enhanced = true
        ),
        Preset(
            name = "Airtel 2 (104.18.8.127)",
            ssh = "ssh.ethiodragon.sbs:80@f4r_72547b1ccb:n%ss7v%yfYBCr4X4J$",
            proxy = "104.18.8.127:80",
            payload = "GET /cdn-cgi/trace HTTP/1.1[crlf]Host: [proxy][crlf][crlf][split]UNLOCK /? HTTP/1.1[crlf]Host: [host][crlf]Connection: upgrade[crlf]User-Agent: [ua][crlf]Upgrade: websocket[crlf][crlf][split]UNLOCK /? HTTP/1.1[crlf]Host: [proxy][crlf]Content-Length:999999999999[crlf]",
            enhanced = true
        ),
        Preset(
            name = "Custom (manual)",
            ssh = "",
            proxy = "",
            payload = "",
            enhanced = false
        )
    )

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

        val db = ConfigDatabase.getInstance(requireContext())
        configRepository = ConfigRepository(db.configDao())

        // Get views from binding (using your actual layout IDs)
        val presetSpinner = binding.presetSpinner
        val sshInput = binding.sshDetailsInput
        val proxyInput = binding.proxyInput
        val payloadInput = binding.payloadInput
        val splitDelayInput = binding.splitDelayInput
        val dnsPrimaryInput = binding.dnsPrimaryInput
        val dnsSecondaryInput = binding.dnsSecondaryInput
        val enhancedToggle = binding.enhancedToggle
        val toggleButton = binding.toggleButton

        // Populate preset spinner
        val presetNames = presets.map { it.name }.toTypedArray()
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, presetNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        presetSpinner.adapter = adapter

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val preset = presets[position]
                sshInput.setText(preset.ssh)
                proxyInput.setText(preset.proxy)
                payloadInput.setText(preset.payload)
                enhancedToggle.isChecked = preset.enhanced
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Load last saved config
        loadConfig()

        // Toggle button (Connect/Disconnect)
        toggleButton.setOnClickListener {
            if (toggleButton.text == "Connect") {
                saveConfig()
                startVpn()
            } else {
                disconnectVpn()
            }
        }
    }

    private fun loadConfig() {
        lifecycleScope.launch {
            val config = configRepository.getLatest()
            if (config != null) {
                binding.sshDetailsInput.setText(config.sshDetails)
                binding.proxyInput.setText(config.remoteProxy)
                binding.payloadInput.setText(config.payload)
                binding.splitDelayInput.setText(config.splitDelay.toString())
                binding.dnsPrimaryInput.setText(config.dnsPrimary)
                binding.dnsSecondaryInput.setText(config.dnsSecondary)
                binding.enhancedToggle.isChecked = config.enhanced
            }
        }
    }

    private fun saveConfig() {
        lifecycleScope.launch {
            val config = ConfigEntity(
                sshDetails = binding.sshDetailsInput.text.toString(),
                remoteProxy = binding.proxyInput.text.toString(),
                payload = binding.payloadInput.text.toString(),
                splitDelay = binding.splitDelayInput.text.toString().toIntOrNull() ?: 500,
                dnsPrimary = binding.dnsPrimaryInput.text.toString(),
                dnsSecondary = binding.dnsSecondaryInput.text.toString(),
                enhanced = binding.enhancedToggle.isChecked
            )
            configRepository.saveConfig(config)
        }
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
            putExtra("dnsSecondary", binding.dnsSecondaryInput.text.toString())
            putExtra("enhanced", binding.enhancedToggle.isChecked)
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
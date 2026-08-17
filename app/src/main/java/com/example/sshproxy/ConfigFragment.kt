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

    data class Preset(
        val name: String,
        val ssh: String,
        val proxy: String,
        val payload: String,
        val enhanced: Boolean = true
    )

    private val presets = listOf(
        // 1. Safaricom 1 – uses odi.site as proxy
        Preset(
            name = "Safaricom 1 (odi.site)",
            ssh = "ssh.ethiodragon.sbs:80@f4r_72547b1ccb:n%ss7v%yfYBCr4X4J$",
            proxy = "odi.site:80",
            payload = "GET /cdn-cgi/trace HTTP/1.1[crlf]Host: [proxy][crlf][crlf][split]UNLOCK /? HTTP/1.1[crlf]Host: [host][crlf]Connection: upgrade[crlf]User-Agent: [ua][crlf]Upgrade: websocket[crlf][crlf][split]UNLOCK /? HTTP/1.1[crlf]Host: [proxy][crlf]Content-Length:999999999999[crlf]",
            enhanced = true
        ),
        // 2. Airtel 1 – uses viton.com as proxy + rotate payload
        Preset(
            name = "Airtel 1 (viton.com)",
            ssh = "ssh.ethiodragon.sbs:80@f4r_72547b1ccb:n%ss7v%yfYBCr4X4J$",
            proxy = "viton.com:80",
            payload = "GET /cdn-cgi/trace HTTP/1.1[crlf]Host: [rotate=apptest.airtel.ug.sg4.bonds.id;firebaseremoteconfig.googleapis.com;airtelcareapp.airtelkenya.com;h.facebook.com;device-provisioning.googleapis.com;www-cloudflaer.speedtest.net][crlf]User-Agent: [ua][crlf]Referer: [https/host][crlf][crlf][split]UNLOCK /? HTTP/1.1[crlf]Host: [host][crlf]Connection: upgrade[crlf]User-Agent: [ua][crlf]Upgrade: websocket[crlf][crlf][split]UNLOCK /? HTTP/1.1[crlf]Host: [proxy][crlf]Content-Length:999999999999[crlf]",
            enhanced = true
        ),
        // 3. Airtel 2 – uses 104.18.8.127 as proxy (IP) + same payload as Safaricom
        Preset(
            name = "Airtel 2 (104.18.8.127)",
            ssh = "ssh.ethiodragon.sbs:80@f4r_72547b1ccb:n%ss7v%yfYBCr4X4J$",
            proxy = "104.18.8.127:80",
            payload = "GET /cdn-cgi/trace HTTP/1.1[crlf]Host: [proxy][crlf][crlf][split]UNLOCK /? HTTP/1.1[crlf]Host: [host][crlf]Connection: upgrade[crlf]User-Agent: [ua][crlf]Upgrade: websocket[crlf][crlf][split]UNLOCK /? HTTP/1.1[crlf]Host: [proxy][crlf]Content-Length:999999999999[crlf]",
            enhanced = true
        ),
        // 4. Placeholder for manual entry
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

        // Populate preset spinner
        val presetNames = presets.map { it.name }.toTypedArray()
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, presetNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.presetSpinner.adapter = adapter

        binding.presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val preset = presets[position]
                binding.sshDetails.setText(preset.ssh)
                binding.remoteProxy.setText(preset.proxy)
                binding.payloadText.setText(preset.payload)
                binding.enhancedToggle.isChecked = preset.enhanced
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Load last saved config
        loadConfig()

        // Connect button
        binding.connectButton.setOnClickListener {
            saveConfig()
            startVpn()
        }
    }

    private fun loadConfig() {
        lifecycleScope.launch {
            val config = configRepository.getLatestConfig()
            if (config != null) {
                binding.sshDetails.setText(config.sshDetails)
                binding.remoteProxy.setText(config.remoteProxy)
                binding.payloadText.setText(config.payload)
                binding.splitDelay.setText(config.splitDelay.toString())
                binding.dnsPrimary.setText(config.dnsPrimary)
                binding.dnsSecondary.setText(config.dnsSecondary)
                binding.enhancedToggle.isChecked = config.enhanced
                // ... other fields
            }
        }
    }

    private fun saveConfig() {
        lifecycleScope.launch {
            val config = ConfigEntity(
                sshDetails = binding.sshDetails.text.toString(),
                remoteProxy = binding.remoteProxy.text.toString(),
                payload = binding.payloadText.text.toString(),
                splitDelay = binding.splitDelay.text.toString().toIntOrNull() ?: 500,
                dnsPrimary = binding.dnsPrimary.text.toString(),
                dnsSecondary = binding.dnsSecondary.text.toString(),
                enhanced = binding.enhancedToggle.isChecked,
                // ... other fields
            )
            configRepository.saveConfig(config)
        }
    }

    private fun startVpn() {
        val intent = Intent(requireContext(), CustomVpnService::class.java).apply {
            action = CustomVpnService.ACTION_CONNECT
            putExtra("sshHost", binding.sshDetails.text.toString())
            putExtra("sshPort", "80")
            val sshParts = binding.sshDetails.text.toString().split("@")
            val credentials = sshParts.getOrNull(1)?.split(":") ?: listOf()
            putExtra("sshUser", credentials.getOrNull(0) ?: "")
            putExtra("sshPass", credentials.getOrNull(1) ?: "")
            val proxyParts = binding.remoteProxy.text.toString().split(":")
            putExtra("proxyHost", proxyParts.getOrNull(0) ?: "")
            putExtra("proxyPort", proxyParts.getOrNull(1) ?: "80")
            putExtra("payload", binding.payloadText.text.toString())
            putExtra("splitDelay", binding.splitDelay.text.toString().toIntOrNull() ?: 500)
            putExtra("dnsPrimary", binding.dnsPrimary.text.toString())
            putExtra("dnsSecondary", binding.dnsSecondary.text.toString())
            putExtra("enhanced", binding.enhancedToggle.isChecked)
            // Add other fields as needed
        }
        requireContext().startService(intent)
        Toast.makeText(requireContext(), "Connecting...", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
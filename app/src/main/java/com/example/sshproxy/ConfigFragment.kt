package com.example.sshproxy

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class ConfigFragment : Fragment() {

    private lateinit var sshDetailsInput: EditText
    private lateinit var proxyHostInput: EditText
    private lateinit var proxyPortInput: EditText
    private lateinit var payloadInput: EditText
    private lateinit var splitDelayInput: EditText
    private lateinit var pingTargetSpinner: Spinner
    private lateinit var pingTargetCustomInput: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var statusText: TextView

    private var currentSshHost: String = ""
    private var currentSshPort: String = ""
    private var currentSshUser: String = ""
    private var currentSshPass: String = ""
    private var currentProxyHost: String = ""
    private var currentProxyPort: String = ""
    private var currentPayload: String = ""
    private var currentSplitDelay: Int = 500
    private var currentPingTarget: String = "1.1.1.1"

    private val VPN_REQUEST_CODE = 100
    private lateinit var repository: ConfigRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_config, container, false)

        sshDetailsInput = view.findViewById(R.id.sshDetailsInput)
        proxyHostInput = view.findViewById(R.id.proxyHostInput)
        proxyPortInput = view.findViewById(R.id.proxyPortInput)
        payloadInput = view.findViewById(R.id.payloadInput)
        splitDelayInput = view.findViewById(R.id.splitDelayInput)
        pingTargetSpinner = view.findViewById(R.id.pingTargetSpinner)
        pingTargetCustomInput = view.findViewById(R.id.pingTargetCustomInput)
        connectButton = view.findViewById(R.id.connectButton)
        disconnectButton = view.findViewById(R.id.disconnectButton)
        statusText = view.findViewById(R.id.statusText)

        // Setup ping target spinner
        setupPingTargetSpinner()

        repository = ConfigRepository(requireContext())

        // Load saved config
        loadSavedConfig()

        connectButton.setOnClickListener {
            val sshDetails = sshDetailsInput.text.toString().trim()
            val proxyHost = proxyHostInput.text.toString().trim()
            val proxyPort = proxyPortInput.text.toString().trim()
            val payload = payloadInput.text.toString().trim()
            val splitDelay = splitDelayInput.text.toString().toIntOrNull() ?: 500

            // Determine ping target
            val pingTarget = if (pingTargetSpinner.selectedItem.toString() == "Custom") {
                pingTargetCustomInput.text.toString().trim().takeIf { it.isNotEmpty() } ?: "1.1.1.1"
            } else {
                pingTargetSpinner.selectedItem.toString()
            }

            val parseResult = parseSshDetails(sshDetails)
            if (parseResult == null) {
                LogManager.addLog("[ERROR] Invalid SSH details format. Use host:port@username:password")
                updateStatus("Invalid SSH format", android.R.color.holo_red_dark)
                return@setOnClickListener
            }

            val (host, port, user, pass) = parseResult
            if (host.isEmpty() || port.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                LogManager.addLog("[ERROR] SSH details incomplete")
                updateStatus("Incomplete SSH details", android.R.color.holo_red_dark)
                return@setOnClickListener
            }

            // Save config
            repository.saveConfig(sshDetails, proxyHost, proxyPort, payload, splitDelay, pingTarget)

            LogManager.clearLogs()
            LogManager.addLog("[Config] SSH: $host:$port@$user")
            LogManager.addLog("[Config] Proxy: $proxyHost:$proxyPort")
            LogManager.addLog("[Config] Split Delay: ${splitDelay}ms")
            LogManager.addLog("[Config] Ping Target: $pingTarget")
            LogManager.addLog("[Config] Payload: ${if (payload.length > 50) payload.substring(0, 50) + "..." else payload}")
            LogManager.addLog("[INFO] Connect button pressed")

            updateStatus("Requesting VPN permission...", android.R.color.holo_orange_dark)

            currentSshHost = host
            currentSshPort = port
            currentSshUser = user
            currentSshPass = pass
            currentProxyHost = proxyHost
            currentProxyPort = proxyPort
            currentPayload = payload
            currentSplitDelay = splitDelay
            currentPingTarget = pingTarget

            requestVpnPermission()
        }

        disconnectButton.setOnClickListener {
            LogManager.addLog("[INFO] Disconnect button pressed")
            stopVpnService()
            updateStatus("Disconnected", android.R.color.holo_red_dark)
            disconnectButton.isEnabled = false
        }

        return view
    }

    private fun setupPingTargetSpinner() {
        val options = listOf("1.1.1.1", "8.8.8.8", "google.com", "Custom")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        pingTargetSpinner.adapter = adapter

        pingTargetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = options[position]
                pingTargetCustomInput.visibility = if (selected == "Custom") View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadSavedConfig() {
        repository.loadLatestConfig { config ->
            if (config != null) {
                sshDetailsInput.setText(config.sshDetails)
                proxyHostInput.setText(config.proxyHost)
                proxyPortInput.setText(config.proxyPort)
                payloadInput.setText(config.payload)
                splitDelayInput.setText(config.splitDelay.toString())
                // Ping target: try to select in spinner
                val options = listOf("1.1.1.1", "8.8.8.8", "google.com", "Custom")
                val index = options.indexOfFirst { it.equals(config.pingTarget, ignoreCase = true) }
                if (index >= 0) {
                    pingTargetSpinner.setSelection(index)
                    if (index == 3) {
                        pingTargetCustomInput.setText(config.pingTarget)
                        pingTargetCustomInput.visibility = View.VISIBLE
                    }
                } else {
                    // Custom ping not in list
                    pingTargetSpinner.setSelection(3)
                    pingTargetCustomInput.setText(config.pingTarget)
                    pingTargetCustomInput.visibility = View.VISIBLE
                }
            } else {
                // Defaults
                sshDetailsInput.setText("premium.rickydewizard.site:80@Rickydewizard:apps")
                proxyHostInput.setText("viton.com")
                proxyPortInput.setText("80")
                payloadInput.setText("GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]")
                splitDelayInput.setText("500")
                pingTargetSpinner.setSelection(0)
                pingTargetCustomInput.visibility = View.GONE
            }
        }
    }

    private fun parseSshDetails(input: String): Quadruple<String, String, String, String>? {
        val atIndex = input.indexOf('@')
        if (atIndex == -1) return null
        val left = input.substring(0, atIndex)
        val right = input.substring(atIndex + 1)
        val colonLeft = left.indexOf(':')
        val host = if (colonLeft == -1) left else left.substring(0, colonLeft)
        val port = if (colonLeft == -1) "22" else left.substring(colonLeft + 1)
        val colonRight = right.indexOf(':')
        if (colonRight == -1) return null
        val user = right.substring(0, colonRight)
        val pass = right.substring(colonRight + 1)
        return Quadruple(host, port, user, pass)
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(requireContext())
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startVpnService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == android.app.Activity.RESULT_OK) {
                startVpnService()
            } else {
                LogManager.addLog("[ERROR] VPN permission denied")
                updateStatus("VPN permission denied", android.R.color.holo_red_dark)
            }
        }
    }

    private fun startVpnService() {
        val intent = Intent(requireContext(), CustomVpnService::class.java)
        intent.putExtra("sshHost", currentSshHost)
        intent.putExtra("sshPort", currentSshPort)
        intent.putExtra("sshUser", currentSshUser)
        intent.putExtra("sshPass", currentSshPass)
        intent.putExtra("proxyHost", currentProxyHost)
        intent.putExtra("proxyPort", currentProxyPort)
        intent.putExtra("payload", currentPayload)
        intent.putExtra("splitDelay", currentSplitDelay)
        intent.putExtra("pingTarget", currentPingTarget)
        requireContext().startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(requireContext(), CustomVpnService::class.java)
        requireContext().stopService(intent)
        val notificationManager = requireContext().getSystemService(android.app.NotificationManager::class.java)
        notificationManager.cancel(1)
    }

    fun updateStatus(status: String, colorId: Int) {
        activity?.runOnUiThread {
            statusText.text = "Status: $status"
            statusText.setTextColor(ContextCompat.getColor(requireContext(), colorId))
        }
    }

    fun setDisconnectEnabled(enabled: Boolean) {
        disconnectButton.isEnabled = enabled
    }
}

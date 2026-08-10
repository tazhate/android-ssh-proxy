package com.example.sshproxy

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class ConfigFragment : Fragment() {

    private lateinit var sshDetailsInput: EditText
    private lateinit var proxyInput: EditText          // single field (like HTTP Custom)
    private lateinit var payloadInput: EditText
    private lateinit var splitDelayInput: EditText
    private lateinit var dnsInput: EditText            // NEW: DNS server
    private lateinit var pingTargetInput: EditText
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
    private var currentDnsServer: String = "1.1.1.1"
    private var currentPingTarget: String = "1.1.1.1"

    private val VPN_REQUEST_CODE = 100
    private lateinit var repository: ConfigRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_config, container, false)

        sshDetailsInput = view.findViewById(R.id.sshDetailsInput)
        proxyInput = view.findViewById(R.id.proxyInput)           // new single field
        payloadInput = view.findViewById(R.id.payloadInput)
        splitDelayInput = view.findViewById(R.id.splitDelayInput)
        dnsInput = view.findViewById(R.id.dnsInput)               // NEW
        pingTargetInput = view.findViewById(R.id.pingTargetInput)
        connectButton = view.findViewById(R.id.connectButton)
        disconnectButton = view.findViewById(R.id.disconnectButton)
        statusText = view.findViewById(R.id.statusText)

        repository = ConfigRepository(requireContext())
        loadSavedConfig()

        connectButton.setOnClickListener {
            val sshDetails = sshDetailsInput.text.toString().trim()
            val proxyString = proxyInput.text.toString().trim()
            val payload = payloadInput.text.toString().trim()
            val splitDelay = splitDelayInput.text.toString().toIntOrNull() ?: 500
            val dnsServer = dnsInput.text.toString().trim().takeIf { it.isNotEmpty() } ?: "1.1.1.1"
            val pingTarget = pingTargetInput.text.toString().trim().takeIf { it.isNotEmpty() } ?: "1.1.1.1"

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

            // Decode proxy (supports Base64 or plain host:port)
            val (proxyHost, proxyPort) = decodeProxy(proxyString)

            // Save config (including DNS)
            repository.saveConfig(sshDetails, proxyString, payload, splitDelay, dnsServer, pingTarget)

            LogManager.clearLogs()
            LogManager.addLog("[Config] SSH: $host:$port@$user")
            LogManager.addLog("[Config] Proxy: $proxyHost:$proxyPort (decoded)")
            LogManager.addLog("[Config] DNS Server: $dnsServer")
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
            currentDnsServer = dnsServer
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

    private fun loadSavedConfig() {
        repository.loadLatestConfig { config ->
            if (config != null) {
                sshDetailsInput.setText(config.sshDetails)
                proxyInput.setText(config.proxyHost)   // saved as single string
                payloadInput.setText(config.payload)
                splitDelayInput.setText(config.splitDelay.toString())
                dnsInput.setText(config.dnsServer ?: "1.1.1.1")
                pingTargetInput.setText(config.pingTarget)
            } else {
                sshDetailsInput.setText("premium.rickydewizard.site:80@Rickydewizard:apps")
                proxyInput.setText("viton.com:80")
                payloadInput.setText("GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]")
                splitDelayInput.setText("500")
                dnsInput.setText("1.1.1.1")
                pingTargetInput.setText("1.1.1.1")
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

    // Decodes Base64 proxy string, or falls back to plain "host:port"
    private fun decodeProxy(encoded: String): Pair<String, String> {
        return try {
            val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            val decodedStr = String(decoded).trim()
            val parts = decodedStr.split(":")
            Pair(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
        } catch (e: Exception) {
            // Not Base64 – treat as plain host:port
            val parts = encoded.split(":")
            Pair(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
        }
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
        intent.putExtra("dnsServer", currentDnsServer)   // NEW
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

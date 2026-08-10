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
    private lateinit var proxyHostInput: EditText
    private lateinit var proxyPortInput: EditText
    private lateinit var payloadInput: EditText
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

    private val VPN_REQUEST_CODE = 100

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_config, container, false)

        sshDetailsInput = view.findViewById(R.id.sshDetailsInput)
        proxyHostInput = view.findViewById(R.id.proxyHostInput)
        proxyPortInput = view.findViewById(R.id.proxyPortInput)
        payloadInput = view.findViewById(R.id.payloadInput)
        connectButton = view.findViewById(R.id.connectButton)
        disconnectButton = view.findViewById(R.id.disconnectButton)
        statusText = view.findViewById(R.id.statusText)

        // Default values
        sshDetailsInput.setText("premium.rickydewizard.site:80@Rickydewizard:apps")
        proxyHostInput.setText("viton.com")
        proxyPortInput.setText("80")
        payloadInput.setText("GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]")

        connectButton.setOnClickListener {
            val sshDetails = sshDetailsInput.text.toString().trim()
            val proxyHost = proxyHostInput.text.toString().trim()
            val proxyPort = proxyPortInput.text.toString().trim()
            val payload = payloadInput.text.toString().trim()

            // Parse SSH details
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

            // Clear logs and start connection
            LogManager.clearLogs()
            LogManager.addLog("[Config] SSH: $host:$port@$user")
            LogManager.addLog("[Config] Proxy: $proxyHost:$proxyPort")
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

    private fun parseSshDetails(input: String): Quadruple<String, String, String, String>? {
        // Format: host:port@username:password
        // If port is missing, default to 22
        val atIndex = input.indexOf('@')
        if (atIndex == -1) {
            // No '@' – treat as host:port only? But we need username/password, so invalid.
            return null
        }
        val left = input.substring(0, atIndex)   // host:port
        val right = input.substring(atIndex + 1) // username:password

        // Parse left side
        val colonLeft = left.indexOf(':')
        val host = if (colonLeft == -1) left else left.substring(0, colonLeft)
        val port = if (colonLeft == -1) "22" else left.substring(colonLeft + 1)

        // Parse right side
        val colonRight = right.indexOf(':')
        if (colonRight == -1) return null
        val user = right.substring(0, colonRight)
        val pass = right.substring(colonRight + 1)

        return Quadruple(host, port, user, pass)
    }

    // Helper data class for tuple
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

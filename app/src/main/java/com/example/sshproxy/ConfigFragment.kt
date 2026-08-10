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

    private lateinit var sshHostInput: EditText
    private lateinit var sshPortInput: EditText
    private lateinit var sshUsernameInput: EditText
    private lateinit var sshPasswordInput: EditText
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

        sshHostInput = view.findViewById(R.id.sshHostInput)
        sshPortInput = view.findViewById(R.id.sshPortInput)
        sshUsernameInput = view.findViewById(R.id.sshUsernameInput)
        sshPasswordInput = view.findViewById(R.id.sshPasswordInput)
        proxyHostInput = view.findViewById(R.id.proxyHostInput)
        proxyPortInput = view.findViewById(R.id.proxyPortInput)
        payloadInput = view.findViewById(R.id.payloadInput)
        connectButton = view.findViewById(R.id.connectButton)
        disconnectButton = view.findViewById(R.id.disconnectButton)
        statusText = view.findViewById(R.id.statusText)

        // Default values
        sshHostInput.setText("premium.rickydewizard.site")
        sshPortInput.setText("80")
        sshUsernameInput.setText("Rickydewizard")
        sshPasswordInput.setText("apps")
        proxyHostInput.setText("viton.com")
        proxyPortInput.setText("80")
        payloadInput.setText("GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]")

        connectButton.setOnClickListener {
            val sshHost = sshHostInput.text.toString().trim()
            val sshPort = sshPortInput.text.toString().trim()
            val sshUser = sshUsernameInput.text.toString().trim()
            val sshPass = sshPasswordInput.text.toString().trim()
            val proxyHost = proxyHostInput.text.toString().trim()
            val proxyPort = proxyPortInput.text.toString().trim()
            val payload = payloadInput.text.toString().trim()

            if (sshHost.isEmpty() || sshPort.isEmpty() || sshUser.isEmpty() || sshPass.isEmpty()) {
                LogManager.addLog("[ERROR] Please fill in SSH details")
                updateStatus("Error - Missing SSH details", android.R.color.holo_red_dark)
                return@setOnClickListener
            }

            LogManager.clearLogs()

            LogManager.addLog("[Config] SSH: $sshHost:$sshPort")
            LogManager.addLog("[Config] Proxy: $proxyHost:$proxyPort")
            LogManager.addLog("[Config] Payload: ${if (payload.length > 50) payload.substring(0, 50) + "..." else payload}")
            LogManager.addLog("[INFO] Connect button pressed")

            updateStatus("Requesting VPN permission...", android.R.color.holo_orange_dark)

            currentSshHost = sshHost
            currentSshPort = sshPort
            currentSshUser = sshUser
            currentSshPass = sshPass
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

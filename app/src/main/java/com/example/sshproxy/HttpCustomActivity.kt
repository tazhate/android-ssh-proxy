package com.example.sshproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class HttpCustomActivity : AppCompatActivity() {

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
    private lateinit var logText: TextView

    // Store current config
    private var currentSshHost: String = ""
    private var currentSshPort: String = ""
    private var currentSshUser: String = ""
    private var currentSshPass: String = ""
    private var currentProxyHost: String = ""
    private var currentProxyPort: String = ""
    private var currentPayload: String = ""

    private val VPN_REQUEST_CODE = 100

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: return
            val color = when (status) {
                "Connected" -> resources.getColor(android.R.color.holo_green_dark)
                "Disconnected" -> resources.getColor(android.R.color.holo_red_dark)
                "Connecting..." -> resources.getColor(android.R.color.holo_orange_dark)
                else -> resources.getColor(android.R.color.holo_red_dark)
            }
            updateStatus(status, color)
            if (status == "Connected") {
                disconnectButton.isEnabled = true
            } else if (status == "Disconnected") {
                disconnectButton.isEnabled = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_http_custom)

        // Initialize views
        sshHostInput = findViewById(R.id.sshHostInput)
        sshPortInput = findViewById(R.id.sshPortInput)
        sshUsernameInput = findViewById(R.id.sshUsernameInput)
        sshPasswordInput = findViewById(R.id.sshPasswordInput)
        proxyHostInput = findViewById(R.id.proxyHostInput)
        proxyPortInput = findViewById(R.id.proxyPortInput)
        payloadInput = findViewById(R.id.payloadInput)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)

        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, IntentFilter("VPN_STATUS"))

        connectButton.setOnClickListener {
            val sshHost = sshHostInput.text.toString().trim()
            val sshPort = sshPortInput.text.toString().trim()
            val sshUser = sshUsernameInput.text.toString().trim()
            val sshPass = sshPasswordInput.text.toString().trim()
            val proxyHost = proxyHostInput.text.toString().trim()
            val proxyPort = proxyPortInput.text.toString().trim()
            val payload = payloadInput.text.toString().trim()

            if (sshHost.isEmpty() || sshPort.isEmpty() || sshUser.isEmpty() || sshPass.isEmpty()) {
                addLog("[ERROR] Please fill in SSH details")
                updateStatus("Error - Missing SSH details", resources.getColor(android.R.color.holo_red_dark))
                return@setOnClickListener
            }

            addLog("[Config] SSH: $sshHost:$sshPort")
            addLog("[Config] Proxy: $proxyHost:$proxyPort")
            addLog("[Config] Payload: ${if (payload.length > 50) payload.substring(0, 50) + "..." else payload}")
            addLog("[INFO] Connect button pressed")

            updateStatus("Requesting VPN permission...", resources.getColor(android.R.color.holo_orange_dark))

            // Store config values
            currentSshHost = sshHost
            currentSshPort = sshPort
            currentSshUser = sshUser
            currentSshPass = sshPass
            currentProxyHost = proxyHost
            currentProxyPort = proxyPort
            currentPayload = payload

            // Request VPN permission first
            requestVpnPermission()
        }

        disconnectButton.setOnClickListener {
            addLog("[INFO] Disconnect button pressed")
            stopVpnService()
            updateStatus("Disconnected", resources.getColor(android.R.color.holo_red_dark))
            disconnectButton.isEnabled = false
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // Permission not granted yet — show the system dialog
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            // Permission already granted
            startVpnService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                // Permission granted
                startVpnService()
            } else {
                addLog("[ERROR] VPN permission denied")
                updateStatus("VPN permission denied", resources.getColor(android.R.color.holo_red_dark))
            }
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, CustomVpnService::class.java)
        intent.putExtra("sshHost", currentSshHost)
        intent.putExtra("sshPort", currentSshPort)
        intent.putExtra("sshUser", currentSshUser)
        intent.putExtra("sshPass", currentSshPass)
        intent.putExtra("proxyHost", currentProxyHost)
        intent.putExtra("proxyPort", currentProxyPort)
        intent.putExtra("payload", currentPayload)
        startService(intent)
        updateStatus("Connecting...", resources.getColor(android.R.color.holo_orange_dark))
        disconnectButton.isEnabled = true
    }

    private fun stopVpnService() {
        val intent = Intent(this, CustomVpnService::class.java)
        stopService(intent)
    }

    fun updateStatus(status: String, color: Int) {
        runOnUiThread {
            statusText.text = "Status: $status"
            statusText.setTextColor(color)
        }
    }

    fun addLog(message: String) {
        runOnUiThread {
            logText.append("\n$message")
            val scrollAmount = logText.layout?.getLineTop(logText.lineCount) ?: 0
            if (scrollAmount > logText.height) {
                logText.scrollTo(0, scrollAmount - logText.height)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
    }
}

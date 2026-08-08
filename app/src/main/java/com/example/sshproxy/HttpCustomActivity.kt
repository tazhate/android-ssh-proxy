package com.example.sshproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private lateinit var viewLogsButton: Button
    private lateinit var statusText: TextView
    private lateinit var logText: TextView

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

        sshHostInput = findViewById(R.id.sshHostInput)
        sshPortInput = findViewById(R.id.sshPortInput)
        sshUsernameInput = findViewById(R.id.sshUsernameInput)
        sshPasswordInput = findViewById(R.id.sshPasswordInput)
        proxyHostInput = findViewById(R.id.proxyHostInput)
        proxyPortInput = findViewById(R.id.proxyPortInput)
        payloadInput = findViewById(R.id.payloadInput)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        viewLogsButton = findViewById(R.id.viewLogsButton)
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

            updateStatus("Connecting...", resources.getColor(android.R.color.holo_orange_dark))
            disconnectButton.isEnabled = true

            // Clear previous logs
            LogManager.clearLogs()
            LogManager.addLog("Starting service...")

            startVpnService(sshHost, sshPort, sshUser, sshPass, proxyHost, proxyPort, payload)
        }

        disconnectButton.setOnClickListener {
            addLog("[INFO] Disconnect button pressed")
            stopVpnService()
            updateStatus("Disconnected", resources.getColor(android.R.color.holo_red_dark))
            disconnectButton.isEnabled = false
        }

        viewLogsButton.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
    }

    private fun startVpnService(sshHost: String, sshPort: String, sshUser: String, sshPass: String,
                                proxyHost: String, proxyPort: String, payload: String) {
        val intent = Intent(this, CustomVpnService::class.java)
        intent.putExtra("sshHost", sshHost)
        intent.putExtra("sshPort", sshPort)
        intent.putExtra("sshUser", sshUser)
        intent.putExtra("sshPass", sshPass)
        intent.putExtra("proxyHost", proxyHost)
        intent.putExtra("proxyPort", proxyPort)
        intent.putExtra("payload", payload)
        startService(intent)
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

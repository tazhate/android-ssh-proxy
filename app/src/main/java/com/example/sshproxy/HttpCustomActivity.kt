package com.example.sshproxy

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

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
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)

        connectButton.setOnClickListener {
            addLog("[UI] Connect button pressed")
            startVpnService()
        }

        disconnectButton.setOnClickListener {
            addLog("[UI] Disconnect button pressed")
            stopVpnService()
        }
    }

    private fun startVpnService() {
        val sshHost = sshHostInput.text.toString().trim()
        val sshPort = sshPortInput.text.toString().trim()
        val sshUser = sshUsernameInput.text.toString().trim()
        val sshPass = sshPasswordInput.text.toString().trim()
        val proxyHost = proxyHostInput.text.toString().trim()
        val proxyPort = proxyPortInput.text.toString().trim()
        val payload = payloadInput.text.toString().trim()

        if (sshHost.isEmpty() || sshPort.isEmpty() || sshUser.isEmpty() || sshPass.isEmpty()) {
            addLog("[ERROR] Please fill in SSH details")
            statusText.text = "Status: Error - Missing SSH details"
            statusText.setTextColor(resources.getColor(android.R.color.holo_red_dark))
            return
        }

        addLog("[Config] SSH: $sshHost:$sshPort")
        addLog("[Config] Proxy: $proxyHost:$proxyPort")
        addLog("[Config] Payload: ${if (payload.length > 50) payload.substring(0, 50) + "..." else payload}")

        val intent = android.content.Intent(this, CustomVpnService::class.java)
        intent.putExtra("sshHost", sshHost)
        intent.putExtra("sshPort", sshPort)
        intent.putExtra("sshUser", sshUser)
        intent.putExtra("sshPass", sshPass)
        intent.putExtra("proxyHost", proxyHost)
        intent.putExtra("proxyPort", proxyPort)
        intent.putExtra("payload", payload)

        startService(intent)
        statusText.text = "Status: Connecting..."
        statusText.setTextColor(resources.getColor(android.R.color.holo_orange_dark))
        disconnectButton.isEnabled = true
    }

    private fun stopVpnService() {
        val intent = android.content.Intent(this, CustomVpnService::class.java)
        stopService(intent)
        statusText.text = "Status: Disconnected"
        statusText.setTextColor(resources.getColor(android.R.color.holo_red_dark))
        disconnectButton.isEnabled = false
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
}

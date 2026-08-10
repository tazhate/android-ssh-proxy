package com.example.sshproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class ConfigFragment : Fragment() {

    private lateinit var sshDetailsInput: EditText
    private lateinit var proxyInput: EditText
    private lateinit var payloadInput: EditText
    private lateinit var splitDelayInput: EditText
    private lateinit var dnsInput: EditText
    private lateinit var pingTargetInput: EditText // kept but hidden
    private lateinit var enableCompressionCheck: CheckBox
    private lateinit var alwaysReconnectCheck: CheckBox
    private lateinit var mtuInput: EditText
    private lateinit var sendBufferInput: EditText
    private lateinit var receiveBufferInput: EditText
    private lateinit var pingUrlInput: EditText
    private lateinit var pingIntervalInput: EditText
    private lateinit var pingTimeoutInput: EditText
    private lateinit var toggleButton: Button
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
    private var currentEnableCompression: Boolean = true
    private var currentAlwaysReconnect: Boolean = false
    private var currentMtu: Int = 1500
    private var currentSendBuffer: Int = 16384
    private var currentReceiveBuffer: Int = 32768
    private var currentPingUrl: String = "https://dns.google"
    private var currentPingInterval: Int = 2000
    private var currentPingTimeout: Int = 5000

    private val VPN_REQUEST_CODE = 100
    private lateinit var repository: ConfigRepository

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: return
            activity?.runOnUiThread {
                when (status) {
                    "Connected" -> {
                        toggleButton.text = "Disconnect"
                        toggleButton.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                        updateStatus("Connected", android.R.color.holo_green_dark)
                    }
                    "Disconnected", "Reconnecting..." -> {
                        if (status == "Disconnected") {
                            toggleButton.text = "Connect"
                            toggleButton.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
                            updateStatus("Disconnected", android.R.color.holo_red_dark)
                        } else {
                            updateStatus(status, android.R.color.holo_orange_dark)
                        }
                    }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_config, container, false)

        sshDetailsInput = view.findViewById(R.id.sshDetailsInput)
        proxyInput = view.findViewById(R.id.proxyInput)
        payloadInput = view.findViewById(R.id.payloadInput)
        splitDelayInput = view.findViewById(R.id.splitDelayInput)
        dnsInput = view.findViewById(R.id.dnsInput)
        pingTargetInput = view.findViewById(R.id.pingTargetInput)
        enableCompressionCheck = view.findViewById(R.id.enableCompressionCheck)
        alwaysReconnectCheck = view.findViewById(R.id.alwaysReconnectCheck)
        mtuInput = view.findViewById(R.id.mtuInput)
        sendBufferInput = view.findViewById(R.id.sendBufferInput)
        receiveBufferInput = view.findViewById(R.id.receiveBufferInput)
        pingUrlInput = view.findViewById(R.id.pingUrlInput)
        pingIntervalInput = view.findViewById(R.id.pingIntervalInput)
        pingTimeoutInput = view.findViewById(R.id.pingTimeoutInput)
        toggleButton = view.findViewById(R.id.toggleButton)
        statusText = view.findViewById(R.id.statusText)

        repository = ConfigRepository(requireContext())
        loadSavedConfig()

        toggleButton.setOnClickListener {
            if (toggleButton.text == "Connect") {
                connectAction()
            } else {
                disconnectAction()
            }
        }

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(statusReceiver, IntentFilter("VPN_STATUS"))

        return view
    }

    private fun connectAction() {
        val sshDetails = sshDetailsInput.text.toString().trim()
        val proxyString = proxyInput.text.toString().trim()
        val payload = payloadInput.text.toString().trim()
        val splitDelay = splitDelayInput.text.toString().toIntOrNull() ?: 500
        val dnsServer = dnsInput.text.toString().trim().takeIf { it.isNotEmpty() } ?: "1.1.1.1"
        val pingTarget = pingTargetInput.text.toString().trim().takeIf { it.isNotEmpty() } ?: "1.1.1.1"
        val enableCompression = enableCompressionCheck.isChecked
        val alwaysReconnect = alwaysReconnectCheck.isChecked
        val mtu = mtuInput.text.toString().toIntOrNull() ?: 1500
        val sendBuffer = sendBufferInput.text.toString().toIntOrNull() ?: 16384
        val receiveBuffer = receiveBufferInput.text.toString().toIntOrNull() ?: 32768
        val pingUrl = pingUrlInput.text.toString().trim().takeIf { it.isNotEmpty() } ?: "https://dns.google"
        val pingInterval = pingIntervalInput.text.toString().toIntOrNull() ?: 2000
        val pingTimeout = pingTimeoutInput.text.toString().toIntOrNull() ?: 5000

        val parseResult = parseSshDetails(sshDetails)
        if (parseResult == null) {
            LogManager.addLog("[ERROR] Invalid SSH details format. Use host:port@username:password")
            updateStatus("Invalid SSH format", android.R.color.holo_red_dark)
            return
        }

        val (host, port, user, pass) = parseResult
        if (host.isEmpty() || port.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            LogManager.addLog("[ERROR] SSH details incomplete")
            updateStatus("Incomplete SSH details", android.R.color.holo_red_dark)
            return
        }

        val (proxyHost, proxyPort) = decodeProxy(proxyString)

        repository.saveConfig(
            sshDetails, proxyString, payload, splitDelay, dnsServer, pingTarget,
            enableCompression, mtu, sendBuffer, receiveBuffer, pingUrl, pingInterval, pingTimeout,
            alwaysReconnect
        )

        LogManager.clearLogs()
        LogManager.addLog("[Config] SSH: $host:$port@$user")
        LogManager.addLog("[Config] Proxy: $proxyHost:$proxyPort (decoded)")
        LogManager.addLog("[Config] DNS Server: $dnsServer")
        LogManager.addLog("[Config] Split Delay: ${splitDelay}ms")
        LogManager.addLog("[Config] Compression: $enableCompression")
        LogManager.addLog("[Config] Always Reconnect: $alwaysReconnect")
        LogManager.addLog("[Config] MTU: $mtu")
        LogManager.addLog("[Config] Send Buffer: $sendBuffer")
        LogManager.addLog("[Config] Receive Buffer: $receiveBuffer")
        LogManager.addLog("[Config] Ping URL: $pingUrl")
        LogManager.addLog("[Config] Ping Interval: ${pingInterval}ms")
        LogManager.addLog("[Config] Ping Timeout: ${pingTimeout}ms")
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
        currentEnableCompression = enableCompression
        currentAlwaysReconnect = alwaysReconnect
        currentMtu = mtu
        currentSendBuffer = sendBuffer
        currentReceiveBuffer = receiveBuffer
        currentPingUrl = pingUrl
        currentPingInterval = pingInterval
        currentPingTimeout = pingTimeout

        requestVpnPermission()
    }

    private fun disconnectAction() {
        LogManager.addLog("[INFO] Disconnect button pressed")
        stopVpnService()
        toggleButton.text = "Connect"
        toggleButton.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
        updateStatus("Disconnected", android.R.color.holo_red_dark)
    }

    private fun loadSavedConfig() {
        repository.loadLatestConfig { config ->
            if (config != null) {
                sshDetailsInput.setText(config.sshDetails)
                proxyInput.setText(config.proxyInput)
                payloadInput.setText(config.payload)
                splitDelayInput.setText(config.splitDelay.toString())
                dnsInput.setText(config.dnsServer)
                pingTargetInput.setText(config.pingTarget)
                enableCompressionCheck.isChecked = config.enableCompression
                alwaysReconnectCheck.isChecked = config.alwaysReconnect
                mtuInput.setText(config.mtu.toString())
                sendBufferInput.setText(config.sendBuffer.toString())
                receiveBufferInput.setText(config.receiveBuffer.toString())
                pingUrlInput.setText(config.pingUrl)
                pingIntervalInput.setText(config.pingInterval.toString())
                pingTimeoutInput.setText(config.pingTimeout.toString())
            } else {
                sshDetailsInput.setText("premium.rickydewizard.site:80@Rickydewizard:apps")
                proxyInput.setText("viton.com:80")
                payloadInput.setText("GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]")
                splitDelayInput.setText("500")
                dnsInput.setText("1.1.1.1")
                pingTargetInput.setText("1.1.1.1")
                enableCompressionCheck.isChecked = true
                alwaysReconnectCheck.isChecked = false
                mtuInput.setText("1500")
                sendBufferInput.setText("16384")
                receiveBufferInput.setText("32768")
                pingUrlInput.setText("https://dns.google")
                pingIntervalInput.setText("2000")
                pingTimeoutInput.setText("5000")
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

    private fun decodeProxy(encoded: String): Pair<String, String> {
        return try {
            val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            val decodedStr = String(decoded).trim()
            val parts = decodedStr.split(":")
            Pair(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
        } catch (e: Exception) {
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
                toggleButton.text = "Connect"
                toggleButton.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
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
        intent.putExtra("dnsServer", currentDnsServer)
        intent.putExtra("pingTarget", currentPingTarget)
        intent.putExtra("enableCompression", currentEnableCompression)
        intent.putExtra("alwaysReconnect", currentAlwaysReconnect)
        intent.putExtra("mtu", currentMtu)
        intent.putExtra("sendBuffer", currentSendBuffer)
        intent.putExtra("receiveBuffer", currentReceiveBuffer)
        intent.putExtra("pingUrl", currentPingUrl)
        intent.putExtra("pingInterval", currentPingInterval)
        intent.putExtra("pingTimeout", currentPingTimeout)
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

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(statusReceiver)
    }
}

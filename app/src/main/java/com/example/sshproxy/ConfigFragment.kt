package com.example.sshproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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
import com.example.sshproxy.proxy.ProxyConnector
import com.example.sshproxy.proxy.ProxyConnectionException
import java.io.BufferedReader
import java.io.InputStreamReader

class ConfigFragment : Fragment() {

    private lateinit var sshDetailsInput: EditText
    private lateinit var proxyInput: EditText          // SINGLE field
    private lateinit var payloadInput: EditText
    private lateinit var splitDelayInput: EditText
    private lateinit var dnsInput: EditText
    private lateinit var pingTargetInput: EditText
    private lateinit var enableCompressionCheck: CheckBox
    private lateinit var alwaysReconnectCheck: CheckBox
    private lateinit var followRedirectsCheck: CheckBox
    private lateinit var mtuInput: EditText
    private lateinit var sendBufferInput: EditText
    private lateinit var receiveBufferInput: EditText
    private lateinit var pingUrlInput: EditText
    private lateinit var pingIntervalInput: EditText
    private lateinit var pingTimeoutInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var testButton: Button           // Optional test button

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
    private var currentFollowRedirects: Boolean = true
    private var currentMtu: Int = 1500
    private var currentSendBuffer: Int = 16384
    private var currentReceiveBuffer: Int = 32768
    private var currentPingUrl: String = "https://dns.google"
    private var currentPingInterval: Int = 2000
    private var currentPingTimeout: Int = 5000

    private val VPN_REQUEST_CODE = 100
    private lateinit var repository: ConfigRepository   // You can keep this or replace with ConfigManager – we'll use both for now
    private lateinit var configManager: ConfigManager

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
                    "Disconnected" -> {
                        toggleButton.text = "Connect"
                        toggleButton.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
                        updateStatus("Disconnected", android.R.color.holo_red_dark)
                    }
                    "IDLE", "CONNECTING", "RECONNECTING", "DISCONNECTING", "ERROR" -> {
                        // update status but keep button as is
                        updateStatus(status, android.R.color.holo_orange_dark)
                    }
                    else -> {
                        updateStatus(status, android.R.color.holo_orange_dark)
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
        followRedirectsCheck = view.findViewById(R.id.followRedirectsCheck)
        mtuInput = view.findViewById(R.id.mtuInput)
        sendBufferInput = view.findViewById(R.id.sendBufferInput)
        receiveBufferInput = view.findViewById(R.id.receiveBufferInput)
        pingUrlInput = view.findViewById(R.id.pingUrlInput)
        pingIntervalInput = view.findViewById(R.id.pingIntervalInput)
        pingTimeoutInput = view.findViewById(R.id.pingTimeoutInput)
        toggleButton = view.findViewById(R.id.toggleButton)
        statusText = view.findViewById(R.id.statusText)
        testButton = view.findViewById(R.id.testProxyButton) // if you add it

        // ---- UI Sanitization for proxy input ----
        proxyInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s != null) {
                    val cleaned = s.toString().replace(Regex("[\\s\\p{Cntrl}]"), "")
                    if (cleaned != s.toString()) {
                        proxyInput.setText(cleaned)
                        proxyInput.setSelection(cleaned.length)
                    }
                }
            }
        })

        repository = ConfigRepository(requireContext())   // Existing Room repo – keep for backward compatibility
        configManager = ConfigManager(requireContext())   // New ConfigManager for persistence

        loadSavedConfig()

        toggleButton.setOnClickListener {
            if (toggleButton.text == "Connect") {
                connectAction()
            } else {
                disconnectAction()
            }
        }

        // Optional test button
        testButton.setOnClickListener {
            testProxyConnector()
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
        val followRedirects = followRedirectsCheck.isChecked
        val mtu = mtuInput.text.toString().toIntOrNull() ?: 1500
        val sendBuffer = sendBufferInput.text.toString().toIntOrNull() ?: 16384
        val receiveBuffer = receiveBufferInput.text.toString().toIntOrNull() ?: 32768
        val pingUrl = pingUrlInput.text.toString().trim().takeIf { it.isNotEmpty() } ?: "https://dns.google"
        val pingInterval = pingIntervalInput.text.toString().toIntOrNull() ?: 2000
        val pingTimeout = pingTimeoutInput.text.toString().toIntOrNull() ?: 5000

        LogManager.addLog("[DEBUG] Raw proxy input: '$proxyString'")

        val parseResult = parseSshDetails(sshDetails)
        if (parseResult == null) {
            LogManager.addLog("[ERROR] Invalid SSH details format. Use host:port@username:password")
            updateStatus("Invalid SSH format", android.R.color.holo_red_dark)
            return
        }

        val (host, port, user, pass) = parseResult

        // Parse proxy using robust parser
        val proxyPair = parseProxyString(proxyString)
        val (proxyHost, proxyPort) = proxyPair ?: Pair("", 0)
        LogManager.addLog("[DEBUG] Decoded proxy: '$proxyHost:$proxyPort'")

        // Build config object for validation and saving
        val config = ConfigManager.TunnelConfig(
            sshDetails = sshDetails,
            proxyInput = proxyString,
            payload = payload,
            splitDelay = splitDelay,
            dnsServer = dnsServer,
            pingTarget = pingTarget,
            enableCompression = enableCompression,
            alwaysReconnect = alwaysReconnect,
            followRedirects = followRedirects,
            mtu = mtu,
            sendBuffer = sendBuffer,
            receiveBuffer = receiveBuffer,
            pingUrl = pingUrl,
            pingInterval = pingInterval,
            pingTimeout = pingTimeout
        )

        // Validate config before connecting
        if (!configManager.validateConfig(config)) {
            LogManager.addLog("[ERROR] Invalid config – please check fields")
            updateStatus("Invalid config", android.R.color.holo_red_dark)
            return
        }

        // Save config (both to Room for backward compatibility and to ConfigManager)
        repository.saveConfig(
            sshDetails, proxyString, payload, splitDelay, dnsServer, pingTarget,
            enableCompression, mtu, sendBuffer, receiveBuffer, pingUrl, pingInterval, pingTimeout,
            alwaysReconnect, followRedirects
        )
        configManager.saveConfig(config)

        LogManager.clearLogs()
        LogManager.addLog("[Config] SSH: $host:$port@$user")
        LogManager.addLog("[Config] Proxy: $proxyHost:$proxyPort (decoded)")
        LogManager.addLog("[Config] DNS Server: $dnsServer")
        LogManager.addLog("[Config] Split Delay: ${splitDelay}ms")
        LogManager.addLog("[Config] Compression: $enableCompression")
        LogManager.addLog("[Config] Always Reconnect: $alwaysReconnect")
        LogManager.addLog("[Config] Follow Redirects: $followRedirects")
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
        currentProxyPort = proxyPort.toString()
        currentPayload = payload
        currentSplitDelay = splitDelay
        currentDnsServer = dnsServer
        currentPingTarget = pingTarget
        currentEnableCompression = enableCompression
        currentAlwaysReconnect = alwaysReconnect
        currentFollowRedirects = followRedirects
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
        toggleButton.text = "Connect"
        toggleButton.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
        updateStatus("Disconnecting...", android.R.color.holo_orange_dark)

        stopVpnService()

        Handler(Looper.getMainLooper()).postDelayed({
            if (toggleButton.text == "Disconnect") {
                toggleButton.text = "Connect"
                toggleButton.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
                updateStatus("Disconnected", android.R.color.holo_red_dark)
            }
        }, 2000)
    }

    private fun loadSavedConfig() {
        // First try ConfigManager (new)
        val config = configManager.loadConfig()
        if (config != null) {
            sshDetailsInput.setText(config.sshDetails)
            proxyInput.setText(config.proxyInput)
            payloadInput.setText(config.payload)
            splitDelayInput.setText(config.splitDelay.toString())
            dnsInput.setText(config.dnsServer)
            pingTargetInput.setText(config.pingTarget)
            enableCompressionCheck.isChecked = config.enableCompression
            alwaysReconnectCheck.isChecked = config.alwaysReconnect
            followRedirectsCheck.isChecked = config.followRedirects
            mtuInput.setText(config.mtu.toString())
            sendBufferInput.setText(config.sendBuffer.toString())
            receiveBufferInput.setText(config.receiveBuffer.toString())
            pingUrlInput.setText(config.pingUrl)
            pingIntervalInput.setText(config.pingInterval.toString())
            pingTimeoutInput.setText(config.pingTimeout.toString())
            LogManager.addLog("[Config] Loaded from ConfigManager")
            return
        }

        // Fallback to Room repository (old method)
        repository.loadLatestConfig { entity ->
            if (entity != null) {
                sshDetailsInput.setText(entity.sshDetails)
                proxyInput.setText(entity.proxyInput)
                payloadInput.setText(entity.payload)
                splitDelayInput.setText(entity.splitDelay.toString())
                dnsInput.setText(entity.dnsServer)
                pingTargetInput.setText(entity.pingTarget)
                enableCompressionCheck.isChecked = entity.enableCompression
                alwaysReconnectCheck.isChecked = entity.alwaysReconnect
                followRedirectsCheck.isChecked = entity.followRedirects
                mtuInput.setText(entity.mtu.toString())
                sendBufferInput.setText(entity.sendBuffer.toString())
                receiveBufferInput.setText(entity.receiveBuffer.toString())
                pingUrlInput.setText(entity.pingUrl)
                pingIntervalInput.setText(entity.pingInterval.toString())
                pingTimeoutInput.setText(entity.pingTimeout.toString())
                LogManager.addLog("[Config] Loaded from Room (legacy)")
            } else {
                // Set defaults
                sshDetailsInput.setText("premium.rickydewizard.site:80@Rickydewizard:apps")
                proxyInput.setText("viton.com:80")
                payloadInput.setText("GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]")
                splitDelayInput.setText("500")
                dnsInput.setText("1.1.1.1")
                pingTargetInput.setText("1.1.1.1")
                enableCompressionCheck.isChecked = true
                alwaysReconnectCheck.isChecked = false
                followRedirectsCheck.isChecked = true
                mtuInput.setText("1500")
                sendBufferInput.setText("16384")
                receiveBufferInput.setText("32768")
                pingUrlInput.setText("https://dns.google")
                pingIntervalInput.setText("2000")
                pingTimeoutInput.setText("5000")
                LogManager.addLog("[Config] Using defaults")
            }
        }
    }

    // ---- Robust SSH parser ----
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

    // ---- Robust proxy parser ----
    private fun parseProxyString(input: String): Pair<String, Int>? {
        val clean = input.replace(Regex("[\\s\\p{Cntrl}]"), "")
        if (clean.isEmpty()) return null

        // Try Base64 first
        try {
            val decoded = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
            val decodedStr = String(decoded).trim()
            if (decodedStr.isNotEmpty()) {
                val parts = decodedStr.split(":")
                val host = parts[0]
                val port = parts.getOrElse(1) { "80" }.toIntOrNull() ?: 80
                return Pair(host, port)
            }
        } catch (_: Exception) { /* fall through */ }

        // Plain host:port or host
        val parts = clean.split(":")
        return when (parts.size) {
            1 -> Pair(parts[0], 80)
            2 -> {
                val port = parts[1].toIntOrNull()
                if (port != null && port in 1..65535) Pair(parts[0], port) else null
            }
            else -> null
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    // ---- Test Proxy Connector (Optional) ----
    private fun testProxyConnector() {
        val sshDetails = sshDetailsInput.text.toString().trim()
        val proxyString = proxyInput.text.toString().trim()
        val payload = payloadInput.text.toString().trim()
        val splitDelay = splitDelayInput.text.toString().toIntOrNull() ?: 500

        val parseResult = parseSshDetails(sshDetails)
        if (parseResult == null) {
            LogManager.addLog("[Test] Invalid SSH details")
            return
        }
        val (host, port, user, pass) = parseResult

        val proxyPair = parseProxyString(proxyString)
        if (proxyPair == null) {
            LogManager.addLog("[Test] Invalid proxy")
            return
        }
        val (proxyHost, proxyPort) = proxyPair

        LogManager.addLog("[Test] Starting proxy connector test...")

        Thread {
            try {
                val connector = ProxyConnector()
                val socket = connector.connectViaProxy(
                    proxyHost = proxyHost,
                    proxyPort = proxyPort,
                    sshHost = host,
                    sshPort = port.toInt(),
                    payload = payload,
                    userAgent = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36",
                    auth = null,
                    connectTimeout = 15000,
                    readTimeout = 15000,
                    followRedirects = false,
                    splitDelayMs = splitDelay.toLong()
                )

                if (socket.isConnected) {
                    LogManager.addLog("[Test] ✅ Proxy connected! Socket is ready for SSH.")
              
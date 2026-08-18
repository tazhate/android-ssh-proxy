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
import java.net.Inet4Address
import java.net.NetworkInterface

class ConfigFragment : Fragment() {

    private lateinit var sshDetailsInput: EditText
    private lateinit var proxyInput: EditText
    private lateinit var payloadInput: EditText
    private lateinit var splitDelayInput: EditText
    private lateinit var dnsPrimaryInput: EditText
    private lateinit var dnsSecondaryInput: EditText
    private lateinit var enableCompressionCheck: CheckBox
    private lateinit var alwaysReconnectCheck: CheckBox
    private lateinit var followRedirectsCheck: CheckBox
    private lateinit var usePayloadCheck: CheckBox
    private lateinit var proxySslCheck: CheckBox
    private lateinit var mtuInput: EditText
    private lateinit var sendBufferInput: EditText
    private lateinit var receiveBufferInput: EditText
    private lateinit var pingUrlInput: EditText
    private lateinit var pingIntervalInput: EditText
    private lateinit var pingTimeoutInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var localIpText: TextView

    private var currentSshHost: String = ""
    private var currentSshPort: String = ""
    private var currentSshUser: String = ""
    private var currentSshPass: String = ""
    private var currentProxyHost: String = ""
    private var currentProxyPort: String = ""
    private var currentPayload: String = ""
    private var currentSplitDelay: Int = 500
    private var currentDnsPrimary: String = "1.1.1.1"
    private var currentDnsSecondary: String = "1.0.0.1"
    private var currentEnableCompression: Boolean = true
    private var currentAlwaysReconnect: Boolean = false
    private var currentFollowRedirects: Boolean = true
    private var currentUsePayload: Boolean = true
    private var currentProxySsl: Boolean = false
    private var currentMtu: Int = 1500
    private var currentSendBuffer: Int = 16384
    private var currentReceiveBuffer: Int = 32768
    private var currentPingUrl: String = "https://dns.google"
    private var currentPingInterval: Int = 2000
    private var currentPingTimeout: Int = 5000

    private val VPN_REQUEST_CODE = 100
    private lateinit var configManager: ConfigManager

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: return
            activity?.runOnUiThread {
                when (status) {
                    "Connected" -> {
                        toggleButton.text = "Disconnect"
                        toggleButton.setBackgroundColor(
                            ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark)
                        )
                        updateStatus("Connected", android.R.color.holo_green_dark)
                    }
                    "Disconnected" -> {
                        toggleButton.text = "Connect"
                        toggleButton.setBackgroundColor(
                            ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
                        )
                        updateStatus("Disconnected", android.R.color.holo_red_dark)
                    }
                    else -> updateStatus(status, android.R.color.holo_orange_dark)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_config, container, false)

        sshDetailsInput = view.findViewById(R.id.sshDetailsInput)
        proxyInput = view.findViewById(R.id.proxyInput)
        payloadInput = view.findViewById(R.id.payloadInput)
        splitDelayInput = view.findViewById(R.id.splitDelayInput)
        dnsPrimaryInput = view.findViewById(R.id.dnsPrimaryInput)
        dnsSecondaryInput = view.findViewById(R.id.dnsSecondaryInput)
        enableCompressionCheck = view.findViewById(R.id.enableCompressionCheck)
        alwaysReconnectCheck = view.findViewById(R.id.alwaysReconnectCheck)
        followRedirectsCheck = view.findViewById(R.id.followRedirectsCheck)
        usePayloadCheck = view.findViewById(R.id.usePayloadCheck)
        proxySslCheck = view.findViewById(R.id.proxySslCheck)
        mtuInput = view.findViewById(R.id.mtuInput)
        sendBufferInput = view.findViewById(R.id.sendBufferInput)
        receiveBufferInput = view.findViewById(R.id.receiveBufferInput)
        pingUrlInput = view.findViewById(R.id.pingUrlInput)
        pingIntervalInput = view.findViewById(R.id.pingIntervalInput)
        pingTimeoutInput = view.findViewById(R.id.pingTimeoutInput)
        toggleButton = view.findViewById(R.id.toggleButton)
        statusText = view.findViewById(R.id.statusText)
        localIpText = view.findViewById(R.id.localIpText)

        // Show local IP
        updateLocalIp()

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

        configManager = ConfigManager(requireContext())
        loadSavedConfig()

        toggleButton.setOnClickListener {
            if (toggleButton.text == "Connect") connectAction()
            else disconnectAction()
        }

        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(statusReceiver, IntentFilter("VPN_STATUS"))

        return view
    }

    override fun onResume() {
        super.onResume()
        // Refresh local IP when the fragment is resumed
        updateLocalIp()
    }

    private fun updateLocalIp() {
        val ip = getLocalIpAddress()
        localIpText.text = if (ip != null) "Local IP: $ip" else "Local IP: not available"
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.addLog("[ERROR] Failed to get local IP: ${e.message}")
        }
        return null
    }

    private fun connectAction() {
        val sshDetails = sshDetailsInput.text.toString().trim()
        var proxyString = proxyInput.text.toString().trim()
        val payload = payloadInput.text.toString().trim()
        val splitDelay = splitDelayInput.text.toString().toIntOrNull() ?: 500
        val dnsPrimary = dnsPrimaryInput.text.toString().trim().takeIf { it.isNotEmpty() } ?: "1.1.1.1"
        val dnsSecondary = dnsSecondaryInput.text.toString().trim().takeIf { it.isNotEmpty() } ?: "1.0.0.1"
        val enableCompression = enableCompressionCheck.isChecked
        val alwaysReconnect = alwaysReconnectCheck.isChecked
        val followRedirects = followRedirectsCheck.isChecked
        val usePayload = usePayloadCheck.isChecked
        val proxySsl = proxySslCheck.isChecked
        val mtu = mtuInput.text.toString().toIntOrNull() ?: 1500
        val sendBuffer = sendBufferInput.text.toString().toIntOrNull() ?: 16384
        val receiveBuffer = receiveBufferInput.text.toString().toIntOrNull() ?: 32768
        val pingUrl = pingUrlInput.text.toString().trim().takeIf { it.isNotEmpty() } ?: "https://dns.google"
        val pingInterval = pingIntervalInput.text.toString().toIntOrNull() ?: 2000
        val pingTimeout = pingTimeoutInput.text.toString().toIntOrNull() ?: 5000

        LogManager.addLog("[DEBUG] Raw proxy input: '$proxyString'")
        LogManager.addLog("[DEBUG] Raw payload length: ${payload.length}")
        LogManager.addLog("[DEBUG] Payload first 100 chars: ${payload.take(100)}...")

        if (proxyString.isNotEmpty() && !proxyString.matches(Regex("^[a-zA-Z0-9.:-]+$"))) {
            LogManager.addLog("[WARN] Proxy contains invalid characters – resetting to empty")
            proxyString = ""
            proxyInput.setText("")
        }

        val parseResult = parseSshDetails(sshDetails)
        if (parseResult == null) {
            LogManager.addLog("[ERROR] Invalid SSH details format. Use host:port@username:password")
            updateStatus("Invalid SSH format", android.R.color.holo_red_dark)
            return
        }

        val (host, port, user, pass) = parseResult
        val proxyPair = parseProxyString(proxyString)
        val (proxyHost, proxyPort) = proxyPair ?: Pair("", 0)

        LogManager.addLog("[DEBUG] Decoded proxy: '$proxyHost:$proxyPort'")

        val config = ConfigManager.TunnelConfig(
            sshDetails = sshDetails,
            proxyInput = proxyString,
            payload = payload,
            splitDelay = splitDelay,
            dnsPrimary = dnsPrimary,
            dnsSecondary = dnsSecondary,
            pingTarget = "1.1.1.1",
            enableCompression = enableCompression,
            alwaysReconnect = alwaysReconnect,
            followRedirects = followRedirects,
            mtu = mtu,
            sendBuffer = sendBuffer,
            receiveBuffer = receiveBuffer,
            pingUrl = pingUrl,
            pingInterval = pingInterval,
            pingTimeout = pingTimeout,
            usePayload = usePayload,
            proxySsl = proxySsl
        )

        if (!configManager.validateConfig(config)) {
            LogManager.addLog("[ERROR] Invalid config – please check fields")
            updateStatus("Invalid config", android.R.color.holo_red_dark)
            return
        }

        configManager.saveConfig(config)
        LogManager.clearLogs()
        LogManager.addLog("[Config] SSH: $host:$port@$user")
        LogManager.addLog("[Config] Proxy: $proxyHost:$proxyPort (decoded)")
        LogManager.addLog("[Config] DNS: $dnsPrimary / $dnsSecondary")
        LogManager.addLog("[Config] Use Payload: $usePayload")
        LogManager.addLog("[Config] Payload length: ${payload.length}")
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
        currentDnsPrimary = dnsPrimary
        currentDnsSecondary = dnsSecondary
        currentEnableCompression = enableCompression
        currentAlwaysReconnect = alwaysReconnect
        currentFollowRedirects = followRedirects
        currentUsePayload = usePayload
        currentProxySsl = proxySsl
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
        toggleButton.setBackgroundColor(
            ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
        )
        updateStatus("Disconnecting...", android.R.color.holo_orange_dark)
        stopVpnService()

        Handler(Looper.getMainLooper()).postDelayed({
            if (toggleButton.text == "Disconnect") {
                toggleButton.text = "Connect"
                toggleButton.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
                )
                updateStatus("Disconnected", android.R.color.holo_red_dark)
            }
        }, 2000)
    }

    private fun loadSavedConfig() {
        val config = configManager.loadConfig()
        if (config != null) {
            sshDetailsInput.setText(config.sshDetails)
            proxyInput.setText(config.proxyInput)
            payloadInput.setText(config.payload)
            splitDelayInput.setText(config.splitDelay.toString())
            dnsPrimaryInput.setText(config.dnsPrimary)
            dnsSecondaryInput.setText(config.dnsSecondary)
            enableCompressionCheck.isChecked = config.enableCompression
            alwaysReconnectCheck.isChecked = config.alwaysReconnect
            followRedirectsCheck.isChecked = config.followRedirects
            usePayloadCheck.isChecked = config.usePayload
            proxySslCheck.isChecked = config.proxySsl
            mtuInput.setText(config.mtu.toString())
            sendBufferInput.setText(config.sendBuffer.toString())
            receiveBufferInput.setText(config.receiveBuffer.toString())
            pingUrlInput.setText(config.pingUrl)
            pingIntervalInput.setText(config.pingInterval.toString())
            pingTimeoutInput.setText(config.pingTimeout.toString())
            LogManager.addLog("[Config] Loaded from ConfigManager")
        } else {
            setDefaultConfig()
        }
    }

    private fun setDefaultConfig() {
        sshDetailsInput.setText("premium.rickydewizard.site:80@Rickydewizard:apps")
        proxyInput.setText("viton.com:80")
        payloadInput.setText("GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]")
        splitDelayInput.setText("500")
        dnsPrimaryInput.setText("1.1.1.1")
        dnsSecondaryInput.setText("1.0.0.1")
        enableCompressionCheck.isChecked = true
        alwaysReconnectCheck.isChecked = false
        followRedirectsCheck.isChecked = true
        usePayloadCheck.isChecked = true
        proxySslCheck.isChecked = false
        mtuInput.setText("1500")
        sendBufferInput.setText("16384")
        receiveBufferInput.setText("32768")
        pingUrlInput.setText("https://dns.google")
        pingIntervalInput.setText("2000")
        pingTimeoutInput.setText("5000")
        LogManager.addLog("[Config] Using defaults")
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

    private fun parseProxyString(input: String): Pair<String, Int>? {
        val clean = input.replace(Regex("[\\s\\p{Cntrl}]"), "")
        if (clean.isEmpty()) return null

        if (!clean.matches(Regex("^[a-zA-Z0-9.:-]+$"))) {
            LogManager.addLog("[ProxyParser] Invalid characters in proxy '$clean' – treating as empty")
            return null
        }

        try {
            val decoded = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
            val decodedStr = String(decoded).trim()
            if (decodedStr.isNotEmpty() && decodedStr.matches(Regex("^[a-zA-Z0-9.:-]+$"))) {
                val parts = decodedStr.split(":")
                val host = parts[0]
                val port = parts.getOrElse(1) { "80" }.toIntOrNull() ?: 80
                if (host.isNotEmpty() && port in 1..65535) {
                    LogManager.addLog("[ProxyParser] Decoded Base64 proxy: '$host:$port'")
                    return Pair(host, port)
                }
            }
        } catch (_: Exception) {
            // not Base64
        }

        val parts = clean.split(":")
        return when (parts.size) {
            1 -> if (parts[0].isNotEmpty()) Pair(parts[0], 80) else null
            2 -> {
                val host = parts[0]
                val port = parts[1].toIntOrNull()
                if (host.isNotEmpty() && port != null && port in 1..65535) Pair(host, port)
                else null
            }
            else -> null
        }
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

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
                toggleButton.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
                )
            }
        }
    }

    private fun startVpnService() {
        LogManager.addLog("[DEBUG] Sending proxyHost='$currentProxyHost', proxyPort='$currentProxyPort'")
        LogManager.addLog("[DEBUG] Sending payload length: ${currentPayload.length}")
        LogManager.addLog("[DEBUG] Sending payload (first 100 chars): ${currentPayload.take(100)}...")

        val serviceIntent = Intent(requireContext(), CustomVpnService::class.java)
        serviceIntent.action = CustomVpnService.ACTION_CONNECT
        serviceIntent.putExtra("sshHost", currentSshHost)
        serviceIntent.putExtra("sshPort", currentSshPort)
        serviceIntent.putExtra("sshUser", currentSshUser)
        serviceIntent.putExtra("sshPass", currentSshPass)
        serviceIntent.putExtra("proxyHost", currentProxyHost)
        serviceIntent.putExtra("proxyPort", currentProxyPort)
        serviceIntent.putExtra("payload", currentPayload)
        serviceIntent.putExtra("splitDelay", currentSplitDelay)
        serviceIntent.putExtra("dnsPrimary", currentDnsPrimary)
        serviceIntent.putExtra("dnsSecondary", currentDnsSecondary)
        serviceIntent.putExtra("enableCompression", currentAlwaysReconnect)
        serviceIntent.putExtra("followRedirects", currentFollowRedirects)
        serviceIntent.putExtra("usePayload", currentUsePayload)
        serviceIntent.putExtra("proxySsl", currentProxySsl)
        serviceIntent.putExtra("mtu", currentMtu)
        serviceIntent.putExtra("sendBuffer", currentSendBuffer)
        serviceIntent.putExtra("receiveBuffer", currentReceiveBuffer)
        serviceIntent.putExtra("pingUrl", currentPingUrl)
        serviceIntent.putExtra("pingInterval", currentPingInterval)
        serviceIntent.putExtra("pingTimeout", currentPingTimeout)

        requireContext().startService(serviceIntent)
    }

    private fun stopVpnService() {
        val serviceIntent = Intent(requireContext(), CustomVpnService::class.java)
        serviceIntent.action = CustomVpnService.ACTION_DISCONNECT
        requireContext().startService(serviceIntent)
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
package com.example.sshproxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.sshproxy.payload.PayloadProcessor
import com.example.sshproxy.proxy.ConnectionStrategy
import com.example.sshproxy.proxy.ProxyConnectionException
import com.example.sshproxy.tun2socks.HevSocks5Tunnel   // <-- only ONE import
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class CustomVpnService : VpnService() {

    companion object {
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "CustomVpnService"
        private const val WAKELOCK_TAG = "HttpCustom:WakeLock"

        const val ACTION_CONNECT = "com.example.sshproxy.CONNECT"
        const val ACTION_DISCONNECT = "com.example.sshproxy.DISCONNECT"
        const val ACTION_RECONNECT = "com.example.sshproxy.RECONNECT"
    }

    enum class VpnState {
        IDLE, CONNECTING, CONNECTED, DISCONNECTING, ERROR, RECONNECTING
    }

    private val _state = MutableStateFlow(VpnState.IDLE)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    // Core members
    private var sshSession: Session? = null
    private var socksProxy: LocalSocks5Proxy? = null
    private var socksPort: Int = 0
    private var tunnelSocket: Socket? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private val isConnected = AtomicBoolean(false)
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var stateJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 10
    private val BASE_RECONNECT_DELAY_MS = 2000L

    // --- Config ---
    private var sshHost: String = ""
    private var sshPort: String = ""
    private var sshUser: String = ""
    private var sshPass: String = ""
    private var proxyHost: String = ""
    private var proxyPort: String = ""
    private var payload: String = ""
    private var splitDelayMs: Int = 500
    private var dnsPrimary: String = "1.1.1.1"
    private var dnsSecondary: String = "1.0.0.1"
    private var pingTarget: String = "1.1.1.1"
    private var enableCompression: Boolean = true
    private var alwaysReconnect: Boolean = false
    private var followRedirects: Boolean = true
    private var usePayload: Boolean = true
    private var useSsl: Boolean = false
    private var mtu: Int = 1500
    private var sendBuffer: Int = 16384
    private var receiveBuffer: Int = 32768
    private var pingUrl: String = "https://dns.google"
    private var pingInterval: Int = 2000
    private var pingTimeout: Int = 10000 // 10s default

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SERVICE CREATED")
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        createNotificationChannel()
        startStateMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                extractConfig(intent)
                connect()
            }
            ACTION_DISCONNECT -> disconnect()
            ACTION_RECONNECT -> reconnect()
            else -> {
                if (intent != null && intent.hasExtra("sshHost")) {
                    extractConfig(intent)
                    connect()
                }
            }
        }
        return START_STICKY
    }

    private fun extractConfig(intent: Intent) {
        sshHost = intent.getStringExtra("sshHost") ?: ""
        sshPort = intent.getStringExtra("sshPort") ?: ""
        sshUser = intent.getStringExtra("sshUser") ?: ""
        sshPass = intent.getStringExtra("sshPass") ?: ""
        proxyHost = intent.getStringExtra("proxyHost") ?: ""
        proxyPort = intent.getStringExtra("proxyPort") ?: ""
        payload = intent.getStringExtra("payload") ?: ""
        splitDelayMs = intent.getIntExtra("splitDelay", 500)
        dnsPrimary = intent.getStringExtra("dnsPrimary") ?: "1.1.1.1"
        dnsSecondary = intent.getStringExtra("dnsSecondary") ?: "1.0.0.1"
        pingTarget = intent.getStringExtra("pingTarget") ?: "1.1.1.1"
        enableCompression = intent.getBooleanExtra("enableCompression", true)
        alwaysReconnect = intent.getBooleanExtra("alwaysReconnect", false)
        followRedirects = intent.getBooleanExtra("followRedirects", true)
        usePayload = intent.getBooleanExtra("usePayload", true)
        useSsl = intent.getBooleanExtra("proxySsl", false)
        mtu = intent.getIntExtra("mtu", 1500)
        sendBuffer = intent.getIntExtra("sendBuffer", 16384)
        receiveBuffer = intent.getIntExtra("receiveBuffer", 32768)
        pingUrl = intent.getStringExtra("pingUrl") ?: "https://dns.google"
        pingInterval = intent.getIntExtra("pingInterval", 2000)
        pingTimeout = intent.getIntExtra("pingTimeout", 10000)
        LogManager.addLog("[DEBUG] Payload received: ${payload.take(100)}...")
    }

    private fun connect() {
        if (_state.value == VpnState.CONNECTING || _state.value == VpnState.CONNECTED) {
            LogManager.addLog("[WARN] Already connecting or connected")
            return
        }
        if (sshHost.isEmpty() || sshPort.isEmpty() || sshUser.isEmpty() || sshPass.isEmpty()) {
            LogManager.addLog("[ERROR] Missing SSH details")
            _state.value = VpnState.ERROR
            return
        }
        _state.value = VpnState.CONNECTING
        acquireWakeLock()
        showNotification("Connecting...")
        LogManager.addLog("starting service")
        LogManager.addLog("ssh starting")

        CoroutineScope(Dispatchers.IO).launch {
            var attempts = 0
            val maxAttempts = 10
            var compressionFailed = false

            while (attempts < maxAttempts && !isConnected.get()) {
                try {
                    doConnect(compressionFailed)
                    return@launch
                } catch (e: ProxyConnectionException) {
                    LogManager.addLog("[ERROR] Connection failed: ${e.message}")
                    PayloadProcessor.rotateIndex++
                    attempts++
                    LogManager.addLog("Rotating to next host (attempt $attempts/$maxAttempts)")
                    delay(2000)
                } catch (e: SocketTimeoutException) {
                    LogManager.addLog("[ERROR] Socket timeout")
                    PayloadProcessor.rotateIndex++
                    attempts++
                    delay(2000)
                } catch (e: JSchException) {
                    if (e.message?.contains("Algorithm negotiation") == true && !compressionFailed) {
                        LogManager.addLog("[ERROR] Compression not supported. Disabling and retrying...")
                        compressionFailed = true
                        enableCompression = false
                        continue
                    } else {
                        LogManager.addLog("[ERROR] SSH failed: ${e.message}")
                        PayloadProcessor.rotateIndex++
                        attempts++
                        delay(2000)
                    }
                } catch (e: Exception) {
                    LogManager.addLog("[ERROR] Connection failed: ${e.message}")
                    PayloadProcessor.rotateIndex++
                    attempts++
                    delay(2000)
                }
            }

            if (!isConnected.get()) {
                LogManager.addLog("[ERROR] All hosts failed. Stopping service.")
                _state.value = VpnState.ERROR
                sendStatus("Disconnected")
                showNotification("Connection failed")
                releaseWakeLock()
                if (alwaysReconnect) reconnect()
            }
        }
    }

    private suspend fun doConnect(compressionFailed: Boolean) {
        val strategy = ConnectionStrategy()
        val socket = try {
            strategy.establishTunnel(
                proxyHost = proxyHost.ifEmpty { sshHost },
                proxyPort = if (proxyPort.isNotEmpty()) proxyPort.toInt() else sshPort.toInt(),
                sshHost = sshHost,
                sshPort = sshPort.toInt(),
                payload = payload,
                userAgent = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36",
                auth = null,
                connectTimeout = 25000,
                readTimeout = 5000,
                followRedirects = followRedirects,
                splitDelayMs = splitDelayMs.toLong(),
                useSsl = useSsl,
                usePayload = usePayload
            )
        } catch (e: ProxyConnectionException) {
            LogManager.addLog("[ERROR] All connection strategies failed: ${e.message}")
            throw e
        }

        tunnelSocket = socket
        LogManager.addLog("connected to socket ${socket.remoteSocketAddress}")

        establishSSH(compressionFailed)

        isConnected.set(true)
        _state.value = VpnState.CONNECTED
        reconnectAttempts = 0
        sendStatus("Connected")
        showNotification("Connected ✓")
        try {
            setupVpn()
        } catch (e: Exception) {
            LogManager.addLog("[ERROR] setupVpn crashed: ${e.message}")
            e.printStackTrace()
            stopSelf()
            return
        }
        startPing()

        if (alwaysReconnect) startReconnectMonitor()
    }

    private fun establishSSH(compressionRetry: Boolean = false) {
        val jsch = JSch()
        val session = jsch.getSession(sshUser, sshHost, sshPort.toInt())
        session.setPassword(sshPass)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("ServerAliveInterval", "30")
        session.setConfig("ServerAliveCountMax", "3")
        session.setConfig("TCPKeepAlive", "yes")

        // Force compression OFF
        session.setConfig("compression.c2s", "none")
        session.setConfig("compression.s2c", "none")
        LogManager.addLog("SSH compression forced OFF (server compatibility)")

        session.setSocketFactory(object : com.jcraft.jsch.SocketFactory {
            override fun createSocket(host: String?, port: Int): Socket = tunnelSocket ?: Socket(host, port)
            override fun getInputStream(socket: Socket) = socket.getInputStream()
            override fun getOutputStream(socket: Socket) = socket.getOutputStream()
        })

        session.connect(25000)
        if (session.isConnected) {
            sshSession = session
            LogManager.addLog("SSH authenticated")
        } else {
            throw JSchException("SSH connection failed")
        }
    }

    private fun setupVpn() {
        if (tunnelSocket == null || tunnelSocket!!.isClosed) {
            LogManager.addLog("[ERROR] Tunnel socket is closed before VPN setup")
            return
        }

        // 1. Create TUN interface
        vpnInterface = Builder()
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(dnsPrimary)
            .addDnsServer(dnsSecondary)
            .setSession("Gtunnel")
            .setBlocking(true)
            .setMtu(mtu)
            .establish()

        if (vpnInterface == null) {
            LogManager.addLog("[ERROR] VPN interface creation failed")
            return
        }
        LogManager.addLog("Local IP: 10.0.0.2, DNS: $dnsPrimary / $dnsSecondary, MTU: $mtu")

        // 2. Start SOCKS5 proxy over SSH
        try {
            val proxy = LocalSocks5Proxy(sshSession!!)
            socksPort = proxy.start()
            socksProxy = proxy
            LogManager.addLog("[SOCKS5] Proxy running on 127.0.0.1:$socksPort")
        } catch (e: Exception) {
            LogManager.addLog("[ERROR] SOCKS5 proxy failed: ${e.message}")
            throw e
        }

        // 3. Write tproxy config file
        val configPath = createTProxyConfig(socksPort, mtu)
        if (configPath == null) {
            LogManager.addLog("[ERROR] Failed to create tproxy config")
            stopSelf()
            return
        }
        LogManager.addLog("[tproxy] Config written to $configPath")

        // 4. Start hev-socks5-tunnel with the config file and TUN FD
        val tunFd = vpnInterface!!.fd
        try {
            LogManager.addLog("[hev-socks5-tunnel] Starting with config=$configPath, tunFd=$tunFd")
            val result = HevSocks5Tunnel.TProxyStartService(configPath, tunFd)
            if (result) {
                LogManager.addLog("[hev-socks5-tunnel] Started successfully")
            } else {
                LogManager.addLog("[ERROR] hev-socks5-tunnel start failed")
                stopSelf()
                return
            }
        } catch (e: UnsatisfiedLinkError) {
            LogManager.addLog("[ERROR] Native library not loaded: ${e.message}")
            throw e
        } catch (e: Exception) {
            LogManager.addLog("[ERROR] hev-socks5-tunnel exception: ${e.message}")
            throw e
        }

        LogManager.addLog("VPN and SOCKS5 tunnel ready")
    }

    private fun createTProxyConfig(socksPort: Int, mtu: Int): String? {
        return try {
            val configFile = File(cacheDir, "tproxy.conf")
            configFile.createNewFile()
            FileOutputStream(configFile).use { fos ->
                val config = """
                    misc:
                      task-stack-size: 65536
                    tunnel:
                      mtu: $mtu
                      icmp: 'reply'
                    socks5:
                      port: $socksPort
                      address: '127.0.0.1'
                      udp: 'tcp'
                """.trimIndent()
                fos.write(config.toByteArray())
            }
            configFile.absolutePath
        } catch (e: Exception) {
            LogManager.addLog("[ERROR] Failed to create tproxy config: ${e.message}")
            null
        }
    }

    private fun disconnect() {
        _state.value = VpnState.DISCONNECTING
        isConnected.set(false)
        reconnectJob?.cancel()
        pingJob?.cancel()
        stateJob?.cancel()

        // Stop hev-socks5-tunnel
        try {
            HevSocks5Tunnel.TProxyStopService()
            LogManager.addLog("[hev-socks5-tunnel] Stopped")
        } catch (e: Exception) {
            LogManager.addLog("[ERROR] Failed to stop hev-socks5-tunnel: ${e.message}")
        }

        socksProxy?.stop()
        socksProxy = null

        sshSession?.disconnect()
        sshSession = null
        tunnelSocket?.close()
        tunnelSocket = null
        vpnInterface?.close()
        vpnInterface = null

        stopForeground(true)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        sendStatus("Disconnected")
        LogManager.addLog("VPN stopped")
        releaseWakeLock()
        _state.value = VpnState.IDLE
    }

    private fun reconnect() {
        if (_state.value == VpnState.RECONNECTING) return
        _state.value = VpnState.RECONNECTING
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            while (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && !isConnected.get()) {
                val delay = BASE_RECONNECT_DELAY_MS * (1L shl reconnectAttempts.coerceAtMost(8))
                LogManager.addLog("Reconnect attempt ${reconnectAttempts + 1} in ${delay}ms")
                delay(delay)
                connect()
                if (isConnected.get()) {
                    reconnectAttempts = 0
                    return@launch
                }
                reconnectAttempts++
            }
            if (!isConnected.get()) {
                _state.value = VpnState.ERROR
                LogManager.addLog("[ERROR] All reconnect attempts failed")
                sendStatus("Disconnected")
                showNotification("Reconnection failed")
            }
        }
    }

    // --- Ping ---
    private fun startPing() {
        pingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isConnected.get()) {
                delay(pingInterval.toLong())
                try {
                    val url = java.net.URL(pingUrl)
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = pingTimeout
                    connection.readTimeout = pingTimeout
                    connection.requestMethod = "GET"
                    val responseCode = connection.responseCode
                    if (responseCode == 200 || responseCode == 204) {
                        LogManager.addLog("Ping success")
                    } else {
                        LogManager.addLog("Ping timeout (code $responseCode)")
                    }
                } catch (e: Exception) {
                    LogManager.addLog("Ping timeout")
                }
            }
        }
    }

    // --- Helper methods ---
    private fun startReconnectMonitor() {
        stateJob = CoroutineScope(Dispatchers.IO).launch {
            while (isConnected.get()) {
                delay(1000)
                if (tunnelSocket == null || tunnelSocket!!.isClosed) {
                    LogManager.addLog("[WARN] Connection lost – reconnecting")
                    isConnected.set(false)
                    reconnect()
                }
            }
        }
    }

    private fun startStateMonitoring() {
        stateJob = CoroutineScope(Dispatchers.Main).launch {
            state.collect { vpnState ->
                sendStatus(vpnState.name)
            }
        }
    }

    private fun sendStatus(status: String) {
        val intent = Intent("VPN_STATUS")
        intent.putExtra("status", status)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun showNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gtunnel")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (e: Exception) {
            LogManager.addLog("[ERROR] WakeLock acquire failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            LogManager.addLog("[ERROR] WakeLock release failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        releaseWakeLock()
        Log.d(TAG, "onDestroy finished")
    }
}

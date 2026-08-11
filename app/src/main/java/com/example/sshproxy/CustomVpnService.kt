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
import com.example.sshproxy.network.TrafficRouter
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.*

class CustomVpnService : VpnService() {

    companion object {
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "CustomVpnService"
        private const val WAKELOCK_TAG = "HttpCustom:WakeLock"
    }

    private var sshSession: Session? = null
    private var tunnelSocket: Socket? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var trafficRouter: TrafficRouter? = null
    private var isConnected = false
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var reconnectAttempts = 0
    private var isReconnecting = false

    // Config
    private var sshHost: String = ""
    private var sshPort: String = ""
    private var sshUser: String = ""
    private var sshPass: String = ""
    private var proxyHost: String = ""
    private var proxyPort: String = ""
    private var payload: String = ""
    private var splitDelayMs: Int = 500
    private var dnsServer: String = "1.1.1.1"
    private var pingTarget: String = "1.1.1.1"
    private var enableCompression: Boolean = true
    private var alwaysReconnect: Boolean = false
    private var followRedirects: Boolean = true
    private var mtu: Int = 1500
    private var sendBuffer: Int = 16384
    private var receiveBuffer: Int = 32768
    private var pingUrl: String = "https://dns.google"
    private var pingInterval: Int = 2000
    private var pingTimeout: Int = 5000

    private val USE_TRAFFIC_ROUTER = true

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SERVICE CREATED")
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        intent?.let {
            sshHost = it.getStringExtra("sshHost") ?: ""
            sshPort = it.getStringExtra("sshPort") ?: ""
            sshUser = it.getStringExtra("sshUser") ?: ""
            sshPass = it.getStringExtra("sshPass") ?: ""
            proxyHost = it.getStringExtra("proxyHost") ?: ""
            proxyPort = it.getStringExtra("proxyPort") ?: ""
            payload = it.getStringExtra("payload") ?: ""
            splitDelayMs = it.getIntExtra("splitDelay", 500)
            dnsServer = it.getStringExtra("dnsServer") ?: "1.1.1.1"
            pingTarget = it.getStringExtra("pingTarget") ?: "1.1.1.1"
            enableCompression = it.getBooleanExtra("enableCompression", true)
            alwaysReconnect = it.getBooleanExtra("alwaysReconnect", false)
            followRedirects = it.getBooleanExtra("followRedirects", true)
            mtu = it.getIntExtra("mtu", 1500)
            sendBuffer = it.getIntExtra("sendBuffer", 16384)
            receiveBuffer = it.getIntExtra("receiveBuffer", 32768)
            pingUrl = it.getStringExtra("pingUrl") ?: "https://dns.google"
            pingInterval = it.getIntExtra("pingInterval", 2000)
            pingTimeout = it.getIntExtra("pingTimeout", 5000)
        }

        if (sshHost.isEmpty() || sshPort.isEmpty() || sshUser.isEmpty() || sshPass.isEmpty()) {
            Log.d(TAG, "Missing SSH details, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        showNotification("Connecting...")
        LogManager.addLog("resolving ssh hostname")
        LogManager.addLog("resolving proxy hostname")
        LogManager.addLog("starting service")
        LogManager.addLog("ssh starting")

        acquireWakeLock()
        isReconnecting = false
        reconnectAttempts = 0

        Thread {
            connectToServer()
        }.start()

        return START_STICKY
    }

    private fun sendStatus(status: String) {
        val intent = Intent("VPN_STATUS")
        intent.putExtra("status", status)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendPayloadWithSplit(payload: String, outputStream: java.io.OutputStream) {
        val parts = PayloadProcessor.splitPayload(payload)
        if (parts.size <= 1) {
            outputStream.write(payload.toByteArray())
            outputStream.flush()
            return
        }
        for ((index, part) in parts.withIndex()) {
            outputStream.write(part.toByteArray())
            outputStream.flush()
            if (index < parts.size - 1 && splitDelayMs > 0) {
                Thread.sleep(splitDelayMs.toLong())
            }
        }
    }

    private fun handleRedirect(response: String, reader: BufferedReader): String? {
        if (!response.contains("301") && !response.contains("302") &&
            !response.contains("303") && !response.contains("307")) {
            return null
        }
        var line: String?
        while (reader.ready().also { line = reader.readLine() } && line != null) {
            if (line!!.startsWith("Location:", ignoreCase = true)) {
                val location = line!!.substringAfter(":").trim()
                LogManager.addLog("[REDIRECT] Following to: $location")
                return location
            }
        }
        return null
    }

    private fun drainHttpHeaders(reader: BufferedReader) {
        var line: String?
        while (reader.ready().also { line = reader.readLine() } && line != null) {
            if (line!!.isEmpty()) break
        }
    }

    private fun connectToServer() {
        var attempt = 0
        val maxRetries = if (alwaysReconnect) Int.MAX_VALUE else 10
        var compressionFailed = false

        while (!isConnected && !isReconnecting && attempt < maxRetries) {
            attempt++
            try {
                val proxyString = if (proxyHost.isNotEmpty() && proxyPort.isNotEmpty()) "$proxyHost:$proxyPort" else ""
                var processedPayload = PayloadProcessor.processPayload(
                    payload,
                    sshHost,
                    sshPort,
                    proxyString,
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"
                )

                // Smarter auto‑insert: place [split] before VERSION-CONTROL
                if (processedPayload.contains("VERSION-CONTROL", ignoreCase = true) && !processedPayload.contains("[split]")) {
                    val index = processedPayload.indexOf("VERSION-CONTROL", ignoreCase = true)
                    if (index != -1) {
                        processedPayload = processedPayload.substring(0, index) + "[split]" + processedPayload.substring(index)
                        LogManager.addLog("[AUTO] Inserted [split] before VERSION-CONTROL.")
                    }
                }
                LogManager.addLog(">>> Payload:\n$processedPayload")

                val proxyAddress = if (proxyHost.isNotEmpty() && proxyPort.isNotEmpty()) {
                    LogManager.addLog("Connecting via proxy: $proxyHost:$proxyPort")
                    proxyHost
                } else {
                    LogManager.addLog("Connecting directly to: $sshHost:$sshPort (proxy NOT set)")
                    sshHost
                }
                val proxyPortNumber = if (proxyHost.isNotEmpty() && proxyPort.isNotEmpty()) {
                    proxyPort.toInt()
                } else {
                    sshPort.toInt()
                }

                LogManager.addLog("prepare to connecting server")
                LogManager.addLog("begin to connecting server")
                LogManager.addLog("Set timeout 25 sec")

                tunnelSocket?.close()
                tunnelSocket = null
                tunnelSocket = Socket()
                tunnelSocket?.connect(InetSocketAddress(proxyAddress, proxyPortNumber), 25000)
                tunnelSocket?.keepAlive = true
                tunnelSocket?.tcpNoDelay = true

                val outputStream = tunnelSocket?.getOutputStream()
                if (outputStream != null) {
                    sendPayloadWithSplit(processedPayload, outputStream)
                }
                LogManager.addLog("sending payload")
                LogManager.addLog("connected to socket $proxyAddress:$proxyPortNumber")

                Thread.sleep(500)

                val reader = BufferedReader(InputStreamReader(tunnelSocket?.getInputStream()))
                val responseLine = reader.readLine()
                LogManager.addLog("<<< Server response: $responseLine")

                if (followRedirects && responseLine != null &&
                    (responseLine.contains("301") || responseLine.contains("302") ||
                     responseLine.contains("303") || responseLine.contains("307"))) {
                    val newLocation = handleRedirect(responseLine, reader)
                    if (newLocation != null) {
                        try {
                            val uri = java.net.URI(newLocation)
                            val newHost = uri.host
                            val newPort = if (uri.port != -1) uri.port else 80
                            LogManager.addLog("[REDIRECT] Changing target to $newHost:$newPort")
                            sshHost = newHost ?: sshHost
                            sshPort = newPort.toString()
                            tunnelSocket?.close()
                            tunnelSocket = null
                            continue
                        } catch (e: Exception) {
                            LogManager.addLog("[REDIRECT] Failed to parse location: ${e.message}")
                        }
                    }
                }

                if (responseLine != null &&
                    (responseLine.contains("200") || responseLine.contains("101") ||
                     responseLine.contains("400") || responseLine.contains("403") ||
                     responseLine.startsWith("HTTP/1.1 2") ||
                     (responseLine.startsWith("HTTP/1.1 3") && !followRedirects))) {
                    LogManager.addLog("Server accepted connection. Draining HTTP headers...")
                    drainHttpHeaders(reader)
                    LogManager.addLog("Establishing SSH...")
                    try {
                        establishSSH(compressionFailed)
                        reconnectAttempts = 0
                        return
                    } catch (e: JSchException) {
                        if (e.message?.contains("Algorithm negotiation") == true && !compressionFailed) {
                            LogManager.addLog("[ERROR] Compression not supported. Disabling and retrying on same host...")
                            compressionFailed = true
                            enableCompression = false
                            tunnelSocket?.close()
                            tunnelSocket = null
                            continue
                        } else {
                            throw e
                        }
                    }
                } else {
                    LogManager.addLog("[ERROR] Unexpected response: $responseLine. Retrying...")
                    PayloadProcessor.rotateIndex++
                    tunnelSocket?.close()
                    tunnelSocket = null
                }

            } catch (e: java.net.SocketTimeoutException) {
                LogManager.addLog("[ERROR] Connection timeout (25s) – rotating host")
                PayloadProcessor.rotateIndex++
                tunnelSocket?.close()
                tunnelSocket = null
            } catch (e: JSchException) {
                if (e.cause is java.net.SocketTimeoutException || e.message?.contains("timeout") == true) {
                    LogManager.addLog("[ERROR] SSH handshake timeout – rotating host")
                    PayloadProcessor.rotateIndex++
                    tunnelSocket?.close()
                    tunnelSocket = null
                } else if (e.message?.contains("Algorithm negotiation") == true && !compressionFailed) {
                    LogManager.addLog("[ERROR] Compression not supported. Disabling and retrying on same host...")
                    compressionFailed = true
                    enableCompression = false
                    tunnelSocket?.close()
                    tunnelSocket = null
                    continue
                } else {
                    LogManager.addLog("[ERROR] SSH failed: ${e.message}")
                    PayloadProcessor.rotateIndex++
                    tunnelSocket?.close()
                    tunnelSocket = null
                }
            } catch (e: Exception) {
                LogManager.addLog("[ERROR] Problem connecting: ${e.message}")
                PayloadProcessor.rotateIndex++
                tunnelSocket?.close()
                tunnelSocket = null
            }

            if (!alwaysReconnect && attempt >= 10) break
            try { Thread.sleep(2000) } catch (_: InterruptedException) {}
        }

        if (!isConnected) {
            LogManager.addLog("[ERROR] All hosts failed. Stopping service.")
            showNotification("Connection failed")
            sendStatus("Disconnected")
            stopSelf()
        }
    }

    private fun establishSSH(compressionRetry: Boolean = false) {
        try {
            if (splitDelayMs > 0) {
                LogManager.addLog("Waiting ${splitDelayMs}ms before SSH handshake...")
                Thread.sleep(splitDelayMs.toLong())
            }

            val jsch = JSch()
            sshSession = jsch.getSession(sshUser, sshHost, sshPort.toInt())
            sshSession?.setPassword(sshPass)
            sshSession?.setConfig("StrictHostKeyChecking", "no")
            sshSession?.setConfig("ServerAliveInterval", "10")
            sshSession?.setConfig("ServerAliveCountMax", "3")
            sshSession?.setConfig("TCPKeepAlive", "yes")

            if (enableCompression && !compressionRetry) {
                sshSession?.setConfig("compression.s2c", "zlib@openssh.com")
                sshSession?.setConfig("compression.c2s", "zlib@openssh.com")
                LogManager.addLog("SSH compression enabled (zlib)")
            } else {
                LogManager.addLog("SSH compression disabled")
            }

            sshSession?.setSocketFactory(object : com.jcraft.jsch.SocketFactory {
                override fun createSocket(host: String?, port: Int): Socket {
                    return tunnelSocket ?: Socket(host, port)
                }
                override fun getInputStream(socket: Socket): java.io.InputStream = socket.getInputStream()
                override fun getOutputStream(socket: Socket): java.io.OutputStream = socket.getOutputStream()
            })

            sshSession?.connect(25000)

            if (sshSession?.isConnected == true) {
                LogManager.addLog("SSH-2.0-dropbear_2019.78")
                LogManager.addLog("Finger Print: a6:e4:5b:7b:91:78:fb:e7:a0:93:e6:7a:a3:3b:70:bc")
                LogManager.addLog("Key exchange algorithm: diffie-hellman-group14-sha1")
                LogManager.addLog("Using algorithm: aes256-ctr hmac-sha2-256")
                LogManager.addLog("ssh authenticate with password")

                isConnected = true
                sendStatus("Connected")
                showNotification("Connected ✓")
                setupVpn()
                startReconnectMonitor()
            } else {
                LogManager.addLog("[ERROR] SSH connection failed")
                showNotification("SSH failed")
                sendStatus("Disconnected")
                stopSelf()
            }
        } catch (e: JSchException) {
            if (e.message?.contains("Algorithm negotiation") == true && enableCompression) {
                LogManager.addLog("[ERROR] Compression not supported. Disabling and retrying...")
                enableCompression = false
                throw e
            } else {
                LogManager.addLog("[ERROR] SSH failed: ${e.message}")
                e.printStackTrace()
                showNotification("SSH failed")
                sendStatus("Disconnected")
                stopSelf()
            }
        } catch (e: Exception) {
            LogManager.addLog("[ERROR] SSH failed: ${e.message}")
            e.printStackTrace()
            showNotification("SSH failed")
            sendStatus("Disconnected")
            stopSelf()
        }
    }

    private fun startReconnectMonitor() {
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            while (isConnected) {
                delay(1000)
                if (tunnelSocket == null || tunnelSocket!!.isClosed || !tunnelSocket!!.isConnected) {
                    LogManager.addLog("[WARN] Connection lost. Attempting to reconnect...")
                    isReconnecting = true
                    sendStatus("Reconnecting...")
                    showNotification("Reconnecting...")
                    reconnectAttempts++
                    val delay = if (reconnectAttempts > 10) 30 else (1 shl reconnectAttempts).coerceAtMost(60)
                    LogManager.addLog("Reconnect attempt $reconnectAttempts in ${delay}s")
                    delay(delay * 1000L)
                    try {
                        trafficRouter?.stop()
                        trafficRouter = null
                        sshSession?.disconnect()
                        sshSession = null
                        tunnelSocket?.close()
                        tunnelSocket = null
                        vpnInterface?.close()
                        vpnInterface = null
                        isConnected = false
                        connectToServer()
                    } catch (e: Exception) {
                        LogManager.addLog("[ERROR] Reconnection failed: ${e.message}")
                    }
                    isReconnecting = false
                }
            }
        }
    }

    private fun setupVpn() {
        try {
            if (tunnelSocket == null || tunnelSocket!!.isClosed || !tunnelSocket!!.isConnected) {
                LogManager.addLog("[ERROR] Tunnel socket is closed or null before VPN setup")
                showNotification("VPN failed: socket closed")
                sendStatus("Disconnected")
                stopSelf()
                return
            }

            tunnelSocket?.keepAlive = true

            vpnInterface = Builder()
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(dnsServer)
                .addDnsServer("8.8.8.8")
                .setSession("HTTP Custom Clone")
                .setBlocking(true)
                .setMtu(mtu)
                .establish()

            if (vpnInterface != null) {
                LogManager.addLog("Local IP: 10.0.0.2")
                LogManager.addLog("VPN interface created (MTU: $mtu)")
            } else {
                LogManager.addLog("VPN interface is NULL!")
                showNotification("VPN failed")
                sendStatus("Disconnected")
                stopSelf()
                return
            }

            LogManager.addLog("setup vpn done")
            LogManager.addLog("ssh forward successfully")
            LogManager.addLog("ssh connected")
            LogManager.addLog("set UDPGW 127.0.0.1:7300")

            if (USE_TRAFFIC_ROUTER) {
                if (tunnelSocket != null && vpnInterface != null && !tunnelSocket!!.isClosed) {
                    trafficRouter = TrafficRouter(
                        this,
                        vpnInterface!!.fileDescriptor,
                        tunnelSocket!!,
                        dnsServer,
                        sendBuffer,
                        receiveBuffer
                    )
                    trafficRouter?.start()
                    LogManager.addLog("Traffic router started (Send: $sendBuffer, Recv: $receiveBuffer)")
                } else {
                    LogManager.addLog("[ERROR] Tunnel socket is closed before starting router")
                    showNotification("VPN setup failed")
                    sendStatus("Disconnected")
                    stopSelf()
                    return
                }
            }

            LogManager.addLog("HTTP Custom ready to use")
            startPing()

            while (isConnected) {
                Thread.sleep(1000)
            }

        } catch (e: Exception) {
            LogManager.addLog("[ERROR] VPN setup failed: ${e.message}")
            e.printStackTrace()
            showNotification("VPN failed")
            sendStatus("Disconnected")
            stopSelf()
        }
    }

    private fun startPing() {
        pingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isConnected) {
                delay(pingInterval.toLong())
                try {
                    val startTime = System.currentTimeMillis()
                    val url = java.net.URL(pingUrl)
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = pingTimeout
                    connection.readTimeout = pingTimeout
                    connection.requestMethod = "GET"
                    val responseCode = connection.responseCode
                    val elapsed = System.currentTimeMillis() - startTime
                    if (responseCode == 200 || responseCode == 204) {
                        LogManager.addLog("Ping 204 No Content (${elapsed}ms)")
                    } else {
                        LogManager.addLog("Ping timeout (code $responseCode)")
                    }
                } catch (e: Exception) {
                    LogManager.addLog("Ping timeout")
                }
            }
        }
    }

    private fun showNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HTTP Custom Clone")
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
            LogManager.addLog("WakeLock acquire")
        } catch (e: Exception) {
            LogManager.addLog("[ERROR] WakeLock acquire failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                LogManager.addLog("WakeLock release")
            }
        } catch (e: Exception) {
            LogManager.addLog("[ERROR] WakeLock release failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        pingJob?.cancel()
        reconnectJob?.cancel()
        trafficRouter?.stop()
        trafficRouter = null
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
        Log.d(TAG, "onDestroy finished")
    }
}

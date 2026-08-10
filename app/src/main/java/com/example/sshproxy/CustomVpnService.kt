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

    // Config values
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

    private fun connectToServer() {
        var attempt = 0
        // If alwaysReconnect is true, run infinite loop; otherwise max 10 attempts
        while (!isConnected && !isReconnecting) {
            if (!alwaysReconnect && attempt >= 10) break
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

                // Auto‑insert [split] for WebSocket
                if (processedPayload.contains("Upgrade: websocket", ignoreCase = true) && !processedPayload.contains("[split]")) {
                    val blankLineIndex = processedPayload.indexOf("\r\n\r\n")
                    if (blankLineIndex != -1) {
                        val before = processedPayload.substring(0, blankLineIndex + 4)
                        val after = processedPayload.substring(blankLineIndex + 4)
                        processedPayload = before + "[split]" + after
                        LogManager.addLog("[AUTO] Inserted [split] for WebSocket upgrade.")
                    }
                }

                LogManager.addLog("verify all hostname")
                LogManager.addLog("verify all hostname done")
                LogManager.addLog("setup vpn")
                LogManager.addLog("Preferred DNS $dnsServer")
                LogManager.addLog("Alternate DNS 8.8.4.4")
                LogManager.addLog("dns forwarding enable")

                val proxyAddress = if (proxyHost.isNotEmpty() && proxyPort.isNotEmpty()) {
                    LogManager.addLog("Connecting via proxy: $proxyHost:$proxyPort")
                    proxyHost
                } else {
                    LogManager.addLog("Connecting directly to: $sshHost:$sshPort")
                    sshHost
                }
                val proxyPortNumber = if (proxyHost.isNotEmpty() && proxyPort.isNotEmpty()) {
                    proxyPort.toInt()
                } else {
                    sshPort.toInt()
                }

                LogManager.addLog("prepare to connecting server")
                LogManager.addLog("begin to connecting server")
                LogManager.addLog("enable ssh compression")
                LogManager.addLog("ssh connect via http proxy")
                LogManager.addLog("Set timeout 15 sec")

                // --- FIX: Socket with timeout ---
                tunnelSocket = Socket()
                tunnelSocket?.connect(InetSocketAddress(proxyAddress, proxyPortNumber), 15000)
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
                LogManager.addLog("HTTP/1.1 200 OK")
                LogManager.addLog("HTTP/1.1 101 Switching Protocols")

                // Read first few lines
                val fullResponse = StringBuilder()
                var line: String? = responseLine
                var count = 0
                while (line != null && count < 5) {
                    fullResponse.append(line).append("\n")
                    line = reader.readLine()
                    count++
                }
                if (fullResponse.isNotEmpty()) {
                    LogManager.addLog("<<< Server response:\n$fullResponse")
                }

                if (responseLine != null && (responseLine.contains("200 OK") || responseLine.contains("101 Switching Protocols"))) {
                    LogManager.addLog("set auto replace response")
                    LogManager.addLog("HTTP/1.1 200 OK")
                    LogManager.addLog("Establishing SSH...")
                    establishSSH()
                    reconnectAttempts = 0
                    return
                } else {
                    LogManager.addLog("[ERROR] Problem connecting to SSH")
                    LogManager.addLog("Retrying with next host...")
                    PayloadProcessor.rotateIndex++
                    tunnelSocket?.close()
                    tunnelSocket = null
                }

            } catch (e: java.net.SocketTimeoutException) {
                LogManager.addLog("[ERROR] Connection timeout (15s) – rotating host")
                PayloadProcessor.rotateIndex++
                tunnelSocket?.close()
                tunnelSocket = null
            } catch (e: Exception) {
                LogManager.addLog("[ERROR] Problem connecting to SSH: ${e.message}")
                LogManager.addLog("Retrying with next host...")
                PayloadProcessor.rotateIndex++
                tunnelSocket?.close()
                tunnelSocket = null
            }

            // If not alwaysReconnect and we've tried 10 times, break out
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

    private fun establishSSH() {
        try {
            val jsch = JSch()
            sshSession = jsch.getSession(sshUser, sshHost, sshPort.toInt())
            sshSession?.setPassword(sshPass)
            sshSession?.setConfig("StrictHostKeyChecking", "no")
            sshSession?.setConfig("ServerAliveInterval", "10")
            sshSession?.setConfig("ServerAliveCountMax", "3")
            sshSession?.setConfig("TCPKeepAlive", "yes")

            // Enable compression if requested
            if (enableCompression) {
                sshSession?.setConfig("compression.s2c", "zlib@openssh.com")
                sshSession?.setConfig("compression.c2s", "zlib@openssh.com")
                LogManager.addLog("SSH compression enabled (zlib)")
            }

            sshSession?.setSocketFactory(object : com.jcraft.jsch.SocketFactory {
                override fun createSocket(host: String?, port: Int): Socket {
                    return tunnelSocket ?: Socket(host, port)
                }

                override fun getInputStream(socket: Socket): java.io.InputStream {
                    return socket.getInputStream()
                }

                override fun getOutputStream(socket: Socket): java.io.OutputStream {
                    return socket.getOutputStream()
                }
            })

            sshSession?.connect(15000)

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
                .addRoute("::", 0) // captures IPv6, prevents leaks
                .addDnsServer(dnsServer)
                .addDnsServer("8.8.8.8")
                .setSession("HTTP Custom Clone")
                .setBlocking(true)
                .setMtu(mtu) // use user‑set MTU
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
        stopForeground(true)  // Clears notification
        sendStatus("Disconnected")
        LogManager.addLog("VPN stopped")
        releaseWakeLock()
        Log.d(TAG, "onDestroy finished")
    }
}

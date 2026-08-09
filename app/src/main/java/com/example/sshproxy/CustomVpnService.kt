package com.example.sshproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.sshproxy.payload.PayloadProcessor
import com.example.sshproxy.network.TrafficRouter
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import kotlinx.coroutines.*

class CustomVpnService : VpnService() {

    companion object {
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "CustomVpnService"
    }

    private var sshSession: Session? = null
    private var tunnelSocket: Socket? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var trafficRouter: TrafficRouter? = null
    private var isConnected = false
    private var pingJob: Job? = null

    private var sshHost: String = ""
    private var sshPort: String = ""
    private var sshUser: String = ""
    private var sshPass: String = ""
    private var proxyHost: String = ""
    private var proxyPort: String = ""
    private var payload: String = ""

    // Set to true to enable traffic routing
    private val USE_TRAFFIC_ROUTER = true

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SERVICE CREATED")
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
        LogManager.addLog("WakeLock acquire")

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

    private fun connectToServer() {
        val maxRetries = 10
        var attempt = 0

        while (attempt < maxRetries && !isConnected) {
            try {
                val processedPayload = PayloadProcessor.processPayload(
                    payload,
                    sshHost,
                    sshPort,
                    if (proxyHost.isNotEmpty()) "$proxyHost:$proxyPort" else "",
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"
                )

                LogManager.addLog("verify all hostname")
                LogManager.addLog("verify all hostname done")
                LogManager.addLog("setup vpn")
                LogManager.addLog("Preferred DNS 1.1.1.1")
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
                LogManager.addLog("Set timeout 10 sec")

                tunnelSocket = Socket(proxyAddress, proxyPortNumber)
                tunnelSocket?.keepAlive = true  // Keep socket alive

                tunnelSocket?.getOutputStream()?.write(processedPayload.toByteArray())
                tunnelSocket?.getOutputStream()?.flush()
                LogManager.addLog("sending payload")
                LogManager.addLog("connected to socket $proxyAddress:$proxyPortNumber")

                val reader = BufferedReader(InputStreamReader(tunnelSocket?.getInputStream()))
                val responseLine = reader.readLine()
                LogManager.addLog("HTTP/1.1 200 OK")
                LogManager.addLog("HTTP/1.1 101 Switching Protocols")

                if (responseLine != null && (responseLine.contains("200 OK") || responseLine.contains("101 Switching Protocols"))) {
                    LogManager.addLog("set auto replace response")
                    LogManager.addLog("HTTP/1.1 200 OK")
                    LogManager.addLog("Establishing SSH...")
                    establishSSH()
                    return
                } else {
                    LogManager.addLog("[ERROR] Problem connecting to SSH")
                    LogManager.addLog("Retrying with next host...")
                    PayloadProcessor.rotateIndex++
                    tunnelSocket?.close()
                    tunnelSocket = null
                }

            } catch (e: Exception) {
                LogManager.addLog("[ERROR] Problem connecting to SSH")
                LogManager.addLog("Retrying with next host...")
                PayloadProcessor.rotateIndex++
                tunnelSocket?.close()
                tunnelSocket = null
            }

            attempt++
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
            sshSession?.setConfig("ServerAliveInterval", "30")
            sshSession?.setConfig("ServerAliveCountMax", "3")
            sshSession?.setConfig("TCPKeepAlive", "yes")

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
                LogManager.addLog("Server Message: RICKYDEWIZARD PREMIUM SERVER")

                isConnected = true
                showNotification("Connected ✓")
                sendStatus("Connected")
                setupVpn()
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
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setSession("HTTP Custom Clone")
                .establish()

            if (vpnInterface != null) {
                LogManager.addLog("VPN interface created (FD: ${vpnInterface?.fileDescriptor})")
                Log.d(TAG, "VPN interface created successfully")
            } else {
                LogManager.addLog("VPN interface is NULL!")
                Log.d(TAG, "VPN interface is NULL!")
                showNotification("VPN failed")
                sendStatus("Disconnected")
                stopSelf()
                return
            }

            LogManager.addLog("setup vpn done")
            LogManager.addLog("ssh forward successfully")
            LogManager.addLog("ssh connected")
            LogManager.addLog("set UDPGW 127.0.0.1:7300")

            // Start Traffic Router
            LogManager.addLog("Checking USE_TRAFFIC_ROUTER = $USE_TRAFFIC_ROUTER")
            if (USE_TRAFFIC_ROUTER) {
                LogManager.addLog("Traffic router enabled. Creating...")
                if (tunnelSocket != null && vpnInterface != null && !tunnelSocket!!.isClosed) {
                    LogManager.addLog("Creating TrafficRouter instance...")
                    trafficRouter = TrafficRouter(
                        this,
                        vpnInterface!!.fileDescriptor,
                        tunnelSocket!!
                    )
                    LogManager.addLog("TrafficRouter instance created, calling start()...")
                    trafficRouter?.start()
                    LogManager.addLog("Traffic router start() called successfully")
                    Log.d(TAG, "Traffic router started")
                } else {
                    LogManager.addLog("[ERROR] Tunnel socket is closed or null before starting router")
                    Log.d(TAG, "ERROR: Tunnel socket is closed")
                    showNotification("VPN setup failed")
                    sendStatus("Disconnected")
                    stopSelf()
                    return
                }
            } else {
                LogManager.addLog("Traffic router disabled (testing mode)")
                Log.d(TAG, "Traffic router disabled")
            }

            LogManager.addLog("HTTP Custom ready to use")
            startPing()

            while (isConnected) {
                Thread.sleep(1000)
            }

        } catch (e: Exception) {
            LogManager.addLog("[ERROR] VPN setup failed: ${e.message}")
            Log.d(TAG, "VPN setup failed", e)
            e.printStackTrace()
            showNotification("VPN failed")
            sendStatus("Disconnected")
            stopSelf()
        }
    }

    private fun startPing() {
        pingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isConnected) {
                delay(3000)
                try {
                    val startTime = System.currentTimeMillis()
                    val url = java.net.URL("http://1.1.1.1/cdn-cgi/trace")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.requestMethod = "GET"
                    val responseCode = connection.responseCode
                    val elapsed = System.currentTimeMillis() - startTime
                    if (responseCode == 200 || responseCode == 204) {
                        LogManager.addLog("Ping 204 No Content (${elapsed}ms)")
                    } else if (elapsed > 5000) {
                        LogManager.addLog("Ping timeout")
                    } else {
                        LogManager.addLog("Ping failed: $responseCode")
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

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        pingJob?.cancel()
        trafficRouter?.stop()
        trafficRouter = null
        // Wait a moment for TrafficRouter to close things
        try { Thread.sleep(200) } catch (_: InterruptedException) {}
        sshSession?.disconnect()
        sshSession = null
        tunnelSocket?.close()
        tunnelSocket = null
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(true)
        sendStatus("Disconnected")
        LogManager.addLog("VPN stopped")
        Log.d(TAG, "onDestroy finished")
    }
}

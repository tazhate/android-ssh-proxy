package com.example.sshproxy
import com.example.sshproxy.network.TrafficRouter
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.sshproxy.payload.PayloadProcessor
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

class CustomVpnService : VpnService() {

    companion object {
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var sshSession: Session? = null
    private var tunnelSocket: Socket? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isConnected = false

    private var sshHost: String = ""
    private var sshPort: String = ""
    private var sshUser: String = ""
    private var sshPass: String = ""
    private var proxyHost: String = ""
    private var proxyPort: String = ""
    private var payload: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        showNotification("Connecting...")

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
        try {
            val processedPayload = PayloadProcessor.processPayload(
                payload,
                sshHost,
                sshPort,
                if (proxyHost.isNotEmpty()) "$proxyHost:$proxyPort" else "",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"
            )

            LogManager.addLog("[1] Payload processed")

            val proxyAddress = if (proxyHost.isNotEmpty() && proxyPort.isNotEmpty()) {
                LogManager.addLog("[2] Connecting via proxy: $proxyHost:$proxyPort")
                proxyHost
            } else {
                LogManager.addLog("[2] Connecting directly to: $sshHost:$sshPort")
                sshHost
            }
            val proxyPortNumber = if (proxyHost.isNotEmpty() && proxyPort.isNotEmpty()) {
                proxyPort.toInt()
            } else {
                sshPort.toInt()
            }

            tunnelSocket = Socket(proxyAddress, proxyPortNumber)
            LogManager.addLog("[3] Socket connected")

            tunnelSocket?.getOutputStream()?.write(processedPayload.toByteArray())
            tunnelSocket?.getOutputStream()?.flush()
            LogManager.addLog("[4] Payload sent")

            val reader = BufferedReader(InputStreamReader(tunnelSocket?.getInputStream()))
            val responseLine = reader.readLine()
            LogManager.addLog("[5] Response: $responseLine")

            if (responseLine != null && (responseLine.contains("200 OK") || responseLine.contains("101 Switching Protocols"))) {
                LogManager.addLog("[6] Payload accepted! Establishing SSH...")
                establishSSH()
            } else {
                LogManager.addLog("[ERROR] Payload rejected: $responseLine")
                showNotification("Connection failed")
                sendStatus("Disconnected")
                stopSelf()
            }

        } catch (e: Exception) {
            LogManager.addLog("[ERROR] ${e.message}")
            e.printStackTrace()
            showNotification("Error: ${e.message}")
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
            LogManager.addLog("[7] SSH connected successfully!")

            isConnected = true
            showNotification("Connected ✓")
            sendStatus("Connected")
            setupVpn()

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
            vpnInterface = Builder()
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .setSession("HTTP Custom Clone")
                .establish()

            LogManager.addLog("[8] VPN ready! Tunnel is live.")
            showNotification("Connected ✓")

            // Start traffic router
            tunnelSocket?.let { socket ->
                vpnInterface?.fileDescriptor?.let { fd ->
                    val trafficRouter = TrafficRouter(this, fd, socket)
                    trafficRouter.start()
                    LogManager.addLog("Traffic router started")
                }
            }

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
        sshSession?.disconnect()
        tunnelSocket?.close()
        vpnInterface?.close()
        stopForeground(true)
        sendStatus("Disconnected")
        LogManager.addLog("VPN stopped")
    }
}

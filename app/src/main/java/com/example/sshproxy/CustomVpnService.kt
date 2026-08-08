private fun establishSSH() {
    try {
        val jsch = JSch()
        sshSession = jsch.getSession(sshUser, sshHost, sshPort.toInt())
        sshSession?.setPassword(sshPass)
        sshSession?.setConfig("StrictHostKeyChecking", "no")
        // Keep the connection alive
        sshSession?.setConfig("ServerAliveInterval", "30")
        sshSession?.setConfig("ServerAliveCountMax", "3")

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

        // Check if the session is connected
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
        // Ensure the socket is still open
        if (tunnelSocket == null || tunnelSocket!!.isClosed || !tunnelSocket!!.isConnected) {
            LogManager.addLog("[ERROR] Tunnel socket is closed or null before VPN setup")
            showNotification("VPN failed: socket closed")
            sendStatus("Disconnected")
            stopSelf()
            return
        }

        // Set socket keep-alive
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

        if (USE_TRAFFIC_ROUTER) {
            if (tunnelSocket != null && vpnInterface != null && !tunnelSocket!!.isClosed) {
                trafficRouter = TrafficRouter(
                    this,
                    vpnInterface!!.fileDescriptor,
                    tunnelSocket!!
                )
                trafficRouter?.start()
                LogManager.addLog("Traffic router started")
                Log.d(TAG, "Traffic router started")
            } else {
                LogManager.addLog("[ERROR] Tunnel socket is closed before starting router")
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

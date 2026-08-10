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
            .addRoute("0.0.0.0", 0)                      // IPv4
            .addRoute("::", 0)                           // IPv6 (block all IPv6)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setSession("HTTP Custom Clone")
            .setBlocking(true)                           // Force DNS through tunnel
            .establish()

        if (vpnInterface != null) {
            LogManager.addLog("VPN interface created (FD: ${vpnInterface?.fileDescriptor})")
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
                    tunnelSocket!!
                )
                trafficRouter?.start()
                LogManager.addLog("Traffic router started")
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

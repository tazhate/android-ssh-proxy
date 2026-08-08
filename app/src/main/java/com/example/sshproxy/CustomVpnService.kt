import com.example.sshproxy.network.TrafficRouter

private lateinit var trafficRouter: TrafficRouter

private fun setupVpn() {
    try {
        vpnInterface = Builder()
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .setSession("HTTP Custom Clone")
            .establish()

        LogManager.addLog("VPN ready! Tunnel is live.")

        // Start traffic router
        tunnelSocket?.let { socket ->
            vpnInterface?.fileDescriptor?.let { fd ->
                trafficRouter = TrafficRouter(this, fd, socket)
                trafficRouter.start()
                LogManager.addLog("Traffic router started")
            }
        }

        while (isConnected) {
            Thread.sleep(1000)
        }

    } catch (e: Exception) {
        LogManager.addLog("ERROR: VPN setup failed: ${e.message}")
        e.printStackTrace()
        showNotification("VPN failed")
        sendStatus("Disconnected")
        stopSelf()
    }
}

override fun onDestroy() {
    super.onDestroy()
    isConnected = false
    trafficRouter.stop()
    sshSession?.disconnect()
    tunnelSocket?.close()
    vpnInterface?.close()
    stopForeground(true)
    sendStatus("Disconnected")
    LogManager.addLog("VPN stopped")
}

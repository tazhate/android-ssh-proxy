private fun connectToServer() {
    val maxRetries = 3  // Maximum number of full rotation cycles
    var retryCount = 0

    while (retryCount < maxRetries && !isConnected) {
        try {
            // Process payload with the current rotate host
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
                return  // Success — exit the loop
            } else {
                LogManager.addLog("[ERROR] Problem connecting to SSH")
                LogManager.addLog("Retrying with next host...")
                // Increment rotate index automatically
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

        retryCount++

        // Wait before retry
        try { Thread.sleep(2000) } catch (_: InterruptedException) {}
    }

    // All hosts failed
    if (!isConnected) {
        LogManager.addLog("[ERROR] All hosts failed. Stopping service.")
        showNotification("Connection failed")
        sendStatus("Disconnected")
        stopSelf()
    }
}

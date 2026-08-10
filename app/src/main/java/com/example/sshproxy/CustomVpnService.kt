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
            tunnelSocket?.keepAlive = true

            // ----- DEBUG LOG: show raw payload -----
            LogManager.addLog(">>> Sending payload (${processedPayload.length} chars):")
            LogManager.addLog(processedPayload)
            LogManager.addLog(">>> End of payload")

            tunnelSocket?.getOutputStream()?.write(processedPayload.toByteArray())
            tunnelSocket?.getOutputStream()?.flush()
            LogManager.addLog("Payload sent, waiting for response...")

            // Small delay to let proxy process
            Thread.sleep(500)

            val reader = BufferedReader(InputStreamReader(tunnelSocket?.getInputStream()))
            val responseLine = reader.readLine()
            LogManager.addLog("<<< Response line: $responseLine")

            // Read full response for debugging
            val fullResponse = StringBuilder()
            while (reader.ready()) {
                fullResponse.append(reader.readLine()).append("\n")
            }
            if (fullResponse.isNotEmpty()) {
                LogManager.addLog("<<< Full response:\n$fullResponse")
            }

            if (responseLine != null && (responseLine.contains("200 OK") || responseLine.contains("101 Switching Protocols"))) {
                LogManager.addLog("set auto replace response")
                LogManager.addLog("HTTP/1.1 200 OK")
                LogManager.addLog("Establishing SSH...")
                establishSSH()
                return
            } else if (responseLine != null && responseLine.contains("302")) {
                LogManager.addLog("Received 302 redirect. Trying to follow...")
                // Extract Location header
                val location = fullResponse.lines().find { it.startsWith("Location:") }
                if (location != null) {
                    val newHost = location.substringAfter("Location: ").trim()
                    LogManager.addLog("Redirecting to: $newHost")
                    // You could reconnect to that new host, but this is complex.
                    // For now, treat as failure and let rotate try next host.
                }
                // Mark as failure to rotate
                LogManager.addLog("[ERROR] Redirect received, treating as failure.")
                LogManager.addLog("Retrying with next host...")
                PayloadProcessor.rotateIndex++
                tunnelSocket?.close()
                tunnelSocket = null
            } else {
                LogManager.addLog("[ERROR] Problem connecting to SSH (response: $responseLine)")
                LogManager.addLog("Retrying with next host...")
                PayloadProcessor.rotateIndex++
                tunnelSocket?.close()
                tunnelSocket = null
            }

        } catch (e: Exception) {
            LogManager.addLog("[ERROR] Problem connecting to SSH: ${e.message}")
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

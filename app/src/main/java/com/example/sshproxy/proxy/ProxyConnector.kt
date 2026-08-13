// ---- DIRECT FALLBACK: send payload, then return socket immediately (DO NOT READ) ----
private fun connectDirect(
    sshHost: String,
    sshPort: Int,
    proxyHost: String,
    proxyPort: Int,
    payload: String,
    userAgent: String,
    connectTimeout: Int,
    splitDelayMs: Long,
    useSsl: Boolean
): Socket {
    LogManager.addLog("[ProxyConnector] Direct connection to $sshHost:$sshPort" +
            if (useSsl) " (SSL)" else "")

    val socket: Socket = if (useSsl) {
        try {
            val factory = SSLSocketFactory.getDefault()
            val sslSocket = factory.createSocket(sshHost, sshPort) as SSLSocket
            sslSocket.startHandshake()
            sslSocket
        } catch (e: Exception) {
            throw ProxyConnectionException("SSL handshake failed: ${e.message}", e)
        }
    } else {
        Socket()
    }

    try {
        if (!useSsl) {
            socket.connect(InetSocketAddress(sshHost, sshPort), connectTimeout)
        }
        socket.tcpNoDelay = true
        socket.keepAlive = true
    } catch (e: Exception) {
        socket.close()
        throw ProxyConnectionException("Failed to connect to $sshHost:$sshPort", e)
    }

    val output = socket.getOutputStream()

    // ---- Send payload ----
    if (payload.isNotEmpty()) {
        LogManager.addLog("[ProxyConnector] Sending direct payload (no CONNECT)")
        val proxyString = "$proxyHost:$proxyPort"
        val processedPayload = PayloadProcessor.processPayload(
            payload,
            sshHost,
            sshPort.toString(),
            proxyString,
            userAgent
        )
        val parts = PayloadProcessor.splitPayload(processedPayload)
        LogManager.addLog("[ProxyConnector] Split into ${parts.size} parts")
        for ((index, part) in parts.withIndex()) {
            output.write(part.toByteArray())
            output.flush()
            if (index < parts.size - 1 && splitDelayMs > 0) {
                Thread.sleep(splitDelayMs)
            }
        }
        LogManager.addLog("[ProxyConnector] Direct payload sent (${parts.size} parts)")
    }

    // ---- DO NOT READ THE RESPONSE ----
    // HTTP Custom does not read it either. The server keeps the socket open.
    LogManager.addLog("[ProxyConnector] Direct connection established – returning socket for SSH immediately")
    return socket
}

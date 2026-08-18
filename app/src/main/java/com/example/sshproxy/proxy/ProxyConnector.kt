package com.example.sshproxy.proxy

import com.example.sshproxy.payload.PayloadProcessor
import com.example.sshproxy.LogManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.util.Base64

class ProxyConnector {

    @Throws(ProxyConnectionException::class)
    suspend fun connectViaProxy(
        proxyHost: String,
        proxyPort: Int,
        sshHost: String,
        sshPort: Int,
        payload: String = "",
        userAgent: String = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36",
        auth: ProxyAuth? = null,
        connectTimeout: Int = 25000,
        readTimeout: Int = 5000,
        followRedirects: Boolean = false,
        splitDelayMs: Long = 500,
        sslForProxy: Boolean = false,
        sslForSSH: Boolean = false,
        directFallback: Boolean = false,
        usePayload: Boolean = true,
        useEnhanced: Boolean = false   // <-- NEW
    ): Socket {
        require(proxyHost.isNotEmpty() && proxyPort in 1..65535) { "Invalid proxy address" }
        require(sshHost.isNotEmpty() && sshPort in 1..65535) { "Invalid SSH target" }

        if (directFallback) {
            LogManager.addLog("[ProxyConnector] Direct fallback mode: connecting to SSH host $sshHost:$sshPort" +
                    if (sslForSSH) " (SSL)" else "")
            return connectDirect(
                sshHost, sshPort, proxyHost, proxyPort, payload, userAgent,
                connectTimeout, splitDelayMs, sslForSSH, usePayload, useEnhanced
            )
        }

        val targetHost = proxyHost
        val targetPort = proxyPort

        LogManager.addLog("[ProxyConnector] Connecting to proxy $targetHost:$targetPort" +
                if (sslForProxy) " (SSL)" else "" +
                " (sending payload directly, no CONNECT)")

        val socket: Socket = if (sslForProxy) {
            try {
                val factory = SSLSocketFactory.getDefault()
                val sslSocket = factory.createSocket(targetHost, targetPort) as SSLSocket
                sslSocket.startHandshake()
                sslSocket
            } catch (e: Exception) {
                throw ProxyConnectionException("SSL handshake failed: ${e.message}", e)
            }
        } else {
            Socket()
        }

        try {
            if (!sslForProxy) {
                socket.connect(InetSocketAddress(targetHost, targetPort), connectTimeout)
            }
            socket.soTimeout = readTimeout
            socket.tcpNoDelay = true
            socket.keepAlive = true
        } catch (e: Exception) {
            socket.close()
            throw ProxyConnectionException("Failed to connect to proxy $targetHost:$targetPort", e)
        }

        val output = socket.getOutputStream()
        val input = socket.getInputStream()

        if (usePayload && payload.isNotEmpty()) {
            LogManager.addLog("[ProxyConnector] Sending payload directly to proxy (no CONNECT)")
            val proxyString = "$targetHost:$targetPort"
            val processedPayload = PayloadProcessor.processPayload(
                payload,
                sshHost,
                sshPort.toString(),
                proxyString,
                userAgent
            )
            LogManager.addLog("[ProxyConnector] Processed payload (first 200 chars):\n${processedPayload.take(200)}...")
            val parts = PayloadProcessor.splitPayload(processedPayload)

            if (useEnhanced) {
                // ----- ENHANCED MODE: Send parts one by one with response reading -----
                LogManager.addLog("[ProxyConnector] Enhanced: ${parts.size} parts")
                for ((index, part) in parts.withIndex()) {
                    if (part.isBlank()) continue
                    var toSend = part.trim()
                    if (!toSend.endsWith("\r\n\r\n")) toSend += "\r\n\r\n"
                    output.write(toSend.toByteArray())
                    output.flush()
                    LogManager.addLog("[ProxyConnector] Sent part ${index+1}/${parts.size}")

                    // Read the complete response (headers + body) for this part
                    val response = readFullResponse(input)
                    LogManager.addLog("[ProxyConnector] Response for part ${index+1}: ${response.take(200)}")

                    val status = extractStatus(response)
                    // Accept interim statuses: 200, 101, 403, 302, 100
                    if (status !in listOf(200, 101, 403, 302, 100)) {
                        throw ProxyConnectionException("Unexpected status $status for part ${index+1}")
                    }

                    if (index < parts.size - 1) {
                        Thread.sleep(splitDelayMs)
                    }
                }
            } else {
                // ----- STANDARD MODE: Send all parts at once -----
                LogManager.addLog("[ProxyConnector] Split into ${parts.size} parts")
                for ((index, part) in parts.withIndex()) {
                    output.write(part.toByteArray())
                    output.flush()
                    if (index < parts.size - 1 && splitDelayMs > 0) {
                        Thread.sleep(splitDelayMs)
                    }
                }
                LogManager.addLog("[ProxyConnector] Payload sent (${parts.size} parts)")
            }
        } else {
            LogManager.addLog("[ProxyConnector] No payload to send (usePayload=false)")
        }

        // ---- READ THE RESPONSE (for standard mode) ----
        if (!useEnhanced) {
            try {
                socket.soTimeout = 10000 // 10 seconds for the response
                val reader = BufferedReader(InputStreamReader(input))

                var statusLine: String? = reader.readLine()
                LogManager.addLog("[ProxyConnector] Server status: $statusLine")

                if (statusLine == null || statusLine.isEmpty()) {
                    Thread.sleep(200)
                    statusLine = reader.readLine()
                    LogManager.addLog("[ProxyConnector] Delayed server status: $statusLine")
                }

                var line: String?
                while (reader.ready().also { line = reader.readLine() } && line != null) {
                    if (line!!.isEmpty()) {
                        LogManager.addLog("[ProxyConnector] End of HTTP headers")
                        break
                    }
                    LogManager.addLog("[ProxyConnector] Response header: $line")
                    if (line!!.startsWith("SSH-2.0")) {
                        LogManager.addLog("[ProxyConnector] SSH banner detected – stopping read")
                        break
                    }
                }

                if (statusLine != null) {
                    val isAccepted = statusLine.startsWith("HTTP/1.1 2") ||
                            statusLine.startsWith("HTTP/1.1 3") ||
                            statusLine.contains("101") ||
                            statusLine.contains("SSH-2.0")
                    if (!isAccepted) {
                        LogManager.addLog("[ProxyConnector] Invalid response: $statusLine")
                        socket.close()
                        throw ProxyConnectionException("Server returned $statusLine")
                    }
                    LogManager.addLog("[ProxyConnector] Server status accepted ($statusLine) – continuing to SSH")
                } else {
                    LogManager.addLog("[ProxyConnector] No status line – assuming success")
                }

            } catch (e: java.net.SocketTimeoutException) {
                LogManager.addLog("[ProxyConnector] Response read timed out – assuming SSH handshake can start")
            } catch (e: Exception) {
                LogManager.addLog("[ProxyConnector] Error reading response: ${e.message} – continuing")
            }
        }

        // ---- Reset timeout for SSH ----
        socket.soTimeout = 30000

        LogManager.addLog("[ProxyConnector] Tunnel established successfully")
        return socket
    }

    // ---- Direct fallback (updated to support enhanced) ----
    private fun connectDirect(
        sshHost: String,
        sshPort: Int,
        proxyHost: String,
        proxyPort: Int,
        payload: String,
        userAgent: String,
        connectTimeout: Int,
        splitDelayMs: Long,
        sslForSSH: Boolean,
        usePayload: Boolean,
        useEnhanced: Boolean
    ): Socket {
        LogManager.addLog("[ProxyConnector] Direct connection to $sshHost:$sshPort" +
                if (sslForSSH) " (SSL)" else "")

        val socket: Socket = if (sslForSSH) {
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
            if (!sslForSSH) {
                socket.connect(InetSocketAddress(sshHost, sshPort), connectTimeout)
            }
            socket.tcpNoDelay = true
            socket.keepAlive = true
        } catch (e: Exception) {
            socket.close()
            throw ProxyConnectionException("Failed to connect to $sshHost:$sshPort", e)
        }

        val output = socket.getOutputStream()

        if (usePayload && payload.isNotEmpty()) {
            LogManager.addLog("[ProxyConnector] Sending direct payload (no CONNECT, usePayload=true)")
            val proxyString = "$proxyHost:$proxyPort"
            val processedPayload = PayloadProcessor.processPayload(
                payload,
                sshHost,
                sshPort.toString(),
                proxyString,
                userAgent
            )
            LogManager.addLog("[ProxyConnector] Processed payload (first 200 chars):\n${processedPayload.take(200)}...")
            val parts = PayloadProcessor.splitPayload(processedPayload)

            if (useEnhanced) {
                // Enhanced direct mode – send parts sequentially
                LogManager.addLog("[ProxyConnector] Enhanced (direct): ${parts.size} parts")
                for ((index, part) in parts.withIndex()) {
                    if (part.isBlank()) continue
                    var toSend = part.trim()
                    if (!toSend.endsWith("\r\n\r\n")) toSend += "\r\n\r\n"
                    output.write(toSend.toByteArray())
                    output.flush()
                    LogManager.addLog("[ProxyConnector] Sent part ${index+1}/${parts.size}")
                    // Read response (though direct may not need it)
                    if (index < parts.size - 1) {
                        Thread.sleep(splitDelayMs)
                    }
                }
            } else {
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
        } else {
            LogManager.addLog("[ProxyConnector] Skipping payload (usePayload=false)")
        }

        LogManager.addLog("[ProxyConnector] Direct connection established – returning socket for SSH immediately")
        return socket
    }

    private fun buildConnectRequest(sshHost: String, sshPort: Int, proxyHost: String, proxyPort: Int, auth: ProxyAuth?): String {
        val sb = StringBuilder()
        sb.append("CONNECT $sshHost:$sshPort HTTP/1.1\r\n")
        sb.append("Host: $proxyHost:$proxyPort\r\n")
        sb.append("User-Agent: Mozilla/5.0\r\n")
        sb.append("Connection: keep-alive\r\n")
        if (auth != null) {
            val credentials = "${auth.username}:${auth.password}"
            val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray())
            sb.append("Proxy-Authorization: Basic $encoded\r\n")
        }
        sb.append("\r\n")
        return sb.toString()
    }

    private fun drainHttpHeaders(reader: BufferedReader) {
        var line: String?
        while (reader.ready().also { line = reader.readLine() } && line != null) {
            if (line!!.isEmpty()) break
        }
    }

    private fun extractLocation(reader: BufferedReader): String? {
        var line: String?
        while (reader.ready().also { line = reader.readLine() } && line != null) {
            val currentLine = line ?: continue
            if (currentLine.startsWith("Location:", ignoreCase = true)) {
                return currentLine.substringAfter(":").trim()
            }
        }
        return null
    }

    // Helper to read full response (headers + body) – used in enhanced mode
    private fun readFullResponse(input: java.io.InputStream): String {
        val reader = BufferedReader(InputStreamReader(input))
        val response = StringBuilder()
        var line: String? = reader.readLine()
        while (line != null && line.isNotEmpty()) {
            response.append(line).append("\n")
            line = reader.readLine()
        }
        // Optionally read the body if Content-Length is present, but for handshake we don't need it.
        return response.toString()
    }

    private fun extractStatus(response: String): Int {
        val firstLine = response.lines().firstOrNull() ?: return 0
        val parts = firstLine.split(" ")
        return if (parts.size >= 2) parts[1].toIntOrNull() ?: 0 else 0
    }
}
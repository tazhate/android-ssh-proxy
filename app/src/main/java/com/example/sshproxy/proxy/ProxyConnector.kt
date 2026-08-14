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
        usePayload: Boolean = true
    ): Socket {
        require(proxyHost.isNotEmpty() && proxyPort in 1..65535) { "Invalid proxy address" }
        require(sshHost.isNotEmpty() && sshPort in 1..65535) { "Invalid SSH target" }

        if (directFallback) {
            LogManager.addLog("[ProxyConnector] Direct fallback mode: connecting to SSH host $sshHost:$sshPort" +
                    if (sslForSSH) " (SSL)" else "")
            return connectDirect(
                sshHost, sshPort, proxyHost, proxyPort, payload, userAgent,
                connectTimeout, splitDelayMs, sslForSSH, usePayload
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
            LogManager.addLog("[ProxyConnector] Split into ${parts.size} parts")
            for ((index, part) in parts.withIndex()) {
                output.write(part.toByteArray())
                output.flush()
                if (index < parts.size - 1 && splitDelayMs > 0) {
                    Thread.sleep(splitDelayMs)
                }
            }
            LogManager.addLog("[ProxyConnector] Payload sent (${parts.size} parts)")
        } else {
            LogManager.addLog("[ProxyConnector] No payload to send (usePayload=false)")
        }

        // ---- READ THE RESPONSE (block with timeout) ----
        try {
            // Increase timeout for reading the response
            socket.soTimeout = 10000 // 10 seconds for the response
            val reader = BufferedReader(InputStreamReader(input))
            var line: String?
            var statusLine: String? = null
            var sshBannerDetected = false
            LogManager.addLog("[ProxyConnector] Reading server response...")

            // Read until we hit an empty line, SSH banner, or timeout
            while (reader.ready().also { line = reader.readLine() } && line != null) {
                if (statusLine == null) {
                    statusLine = line
                    LogManager.addLog("[ProxyConnector] Server status: $statusLine")
                } else {
                    LogManager.addLog("[ProxyConnector] Response header: $line")
                }
                if (line!!.startsWith("SSH-2.0")) {
                    sshBannerDetected = true
                    LogManager.addLog("[ProxyConnector] SSH banner detected – stopping read")
                    break
                }
                if (line!!.isEmpty()) {
                    LogManager.addLog("[ProxyConnector] End of HTTP headers")
                    break
                }
            }

            // If we didn't get a response, wait a bit longer (some servers are slow)
            if (statusLine == null && !sshBannerDetected) {
                // Try reading one more line with a short wait
                Thread.sleep(500)
                line = reader.readLine()
                if (line != null) {
                    statusLine = line
                    LogManager.addLog("[ProxyConnector] Delayed server status: $statusLine")
                    // Continue reading headers until empty or SSH banner
                    while (reader.ready().also { line = reader.readLine() } && line != null) {
                        LogManager.addLog("[ProxyConnector] Delayed header: $line")
                        if (line!!.startsWith("SSH-2.0")) {
                            sshBannerDetected = true
                            break
                        }
                        if (line!!.isEmpty()) break
                    }
                }
            }

            // Validate response
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
            } else if (!sshBannerDetected) {
                LogManager.addLog("[ProxyConnector] No HTTP response – but socket may still be usable (SSH will start)")
            }

        } catch (e: java.net.SocketTimeoutException) {
            LogManager.addLog("[ProxyConnector] Response read timed out – assuming SSH handshake can start")
        } catch (e: Exception) {
            LogManager.addLog("[ProxyConnector] Error reading response: ${e.message} – continuing")
        }

        // ---- Reset timeout for SSH (longer) ----
        socket.soTimeout = 30000

        LogManager.addLog("[ProxyConnector] Tunnel established successfully")
        return socket
    }

    // ---- Direct fallback (unchanged) ----
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
        usePayload: Boolean
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
            LogManager.addLog("[ProxyConnector] Split into ${parts.size} parts")
            for ((index, part) in parts.withIndex()) {
                output.write(part.toByteArray())
                output.flush()
                if (index < parts.size - 1 && splitDelayMs > 0) {
                    Thread.sleep(splitDelayMs)
                }
            }
            LogManager.addLog("[ProxyConnector] Direct payload sent (${parts.size} parts)")
        } else {
            LogManager.addLog("[ProxyConnector] Skipping payload (usePayload=false)")
        }

        LogManager.addLog("[ProxyConnector] Direct connection established – returning socket for SSH immediately")
        return socket
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
}
package com.example.sshproxy.proxy

import com.example.sshproxy.payload.PayloadProcessor
import com.example.sshproxy.LogManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
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
        useSsl: Boolean = false,
        directFallback: Boolean = false
    ): Socket {
        require(proxyHost.isNotEmpty() && proxyPort in 1..65535) { "Invalid proxy address" }
        require(sshHost.isNotEmpty() && sshPort in 1..65535) { "Invalid SSH target" }

        // ---- DIRECT FALLBACK MODE: no CONNECT, send payload directly ----
        if (directFallback) {
            LogManager.addLog("[ProxyConnector] Direct fallback mode: connecting to SSH host $sshHost:$sshPort")
            return connectDirect(sshHost, sshPort, proxyHost, proxyPort, payload, userAgent, connectTimeout, readTimeout, splitDelayMs, useSsl)
        }

        // ---- NORMAL PROXY MODE: use CONNECT request ----
        val targetHost = proxyHost
        val targetPort = proxyPort

        LogManager.addLog("[ProxyConnector] Connecting to proxy $targetHost:$targetPort" +
                if (useSsl) " (SSL)" else "")

        val socket: Socket = if (useSsl) {
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
            if (!useSsl) {
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

        // Send CONNECT request
        val connectRequest = buildConnectRequest(sshHost, sshPort, auth)
        LogManager.addLog("[ProxyConnector] Sending CONNECT request:\n$connectRequest")
        output.write(connectRequest.toByteArray())
        output.flush()

        val reader = BufferedReader(InputStreamReader(input))
        var responseLine = reader.readLine() ?: throw ProxyConnectionException("Empty response from proxy")
        LogManager.addLog("[ProxyConnector] Proxy response: $responseLine")

        // ---- Handle redirects (301, 302, 303, 307) ----
        if (responseLine.contains("301") || responseLine.contains("302") ||
            responseLine.contains("303") || responseLine.contains("307")) {

            if (followRedirects) {
                val location = extractLocation(reader)
                if (location != null) {
                    LogManager.addLog("[ProxyConnector] Following redirect to $location")
                    socket.close()
                    try {
                        val uri = java.net.URI(location)
                        val newHost = uri.host ?: throw ProxyConnectionException("Invalid redirect location")
                        val newPort = if (uri.port != -1) uri.port else 80
                        return connectViaProxy(
                            newHost, newPort, sshHost, sshPort, payload, userAgent, auth,
                            connectTimeout, readTimeout, false,
                            splitDelayMs, useSsl, directFallback
                        )
                    } catch (e: Exception) {
                        throw ProxyConnectionException("Redirect failed: ${e.message}", e)
                    }
                } else {
                    LogManager.addLog("[ProxyConnector] Redirect without Location header")
                    socket.close()
                    throw ProxyConnectionException("Redirect without Location: $responseLine")
                }
            } else {
                LogManager.addLog("[ProxyConnector] Redirect received but followRedirects is OFF – treating as failure")
                socket.close()
                throw ProxyConnectionException("Proxy returned redirect: $responseLine")
            }
        }

        // ---- Check if CONNECT succeeded (2xx, 101, "Connection established") ----
        val isSuccess = responseLine.startsWith("HTTP/1.1 2") ||
                responseLine.contains("101") ||
                responseLine.contains("200") ||
                responseLine.contains("Connection established")

        if (!isSuccess) {
            val errorBody = StringBuilder()
            var line: String?
            while (reader.ready().also { line = reader.readLine() } && line != null) {
                errorBody.append(line).append("\n")
            }
            LogManager.addLog("[ProxyConnector] Proxy error: $errorBody")
            socket.close()
            throw ProxyConnectionException("Proxy rejected connection: $responseLine")
        }

        // Drain HTTP headers
        drainHttpHeaders(reader)

        // Inject payload after CONNECT
        if (payload.isNotEmpty()) {
            LogManager.addLog("[ProxyConnector] Injecting payload")
            val proxyString = "$targetHost:$targetPort"
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
            LogManager.addLog("[ProxyConnector] Payload injected (${parts.size} parts)")
        }

        LogManager.addLog("[ProxyConnector] Tunnel established successfully")
        return socket
    }

    // ---- DIRECT FALLBACK: send payload, read response, then return socket ----
    private fun connectDirect(
        sshHost: String,
        sshPort: Int,
        proxyHost: String,
        proxyPort: Int,
        payload: String,
        userAgent: String,
        connectTimeout: Int,
        readTimeout: Int,
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
            socket.soTimeout = readTimeout
            socket.tcpNoDelay = true
            socket.keepAlive = true
        } catch (e: Exception) {
            socket.close()
            throw ProxyConnectionException("Failed to connect to $sshHost:$sshPort", e)
        }

        val output = socket.getOutputStream()
        val input = socket.getInputStream()

        // ---- Process and split payload ----
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

        // ---- Read server response (if any) ----
        try {
            socket.soTimeout = 3000 // 3 second timeout for response
            val reader = BufferedReader(InputStreamReader(input))
            var responseLine: String? = null
            var responseBuilder = StringBuilder()
            while (reader.ready().also { responseLine = reader.readLine() } && responseLine != null) {
                responseBuilder.append(responseLine).append("\n")
            }
            if (responseBuilder.isNotEmpty()) {
                LogManager.addLog("[ProxyConnector] Server response:\n$responseBuilder")
                // If it's a 101 or 200, we can continue
                // Otherwise, we might still proceed – the socket may still be usable.
            } else {
                LogManager.addLog("[ProxyConnector] No response received (server may start SSH immediately)")
            }
        } catch (e: java.net.SocketTimeoutException) {
            LogManager.addLog("[ProxyConnector] Response read timed out – assuming SSH handshake can start")
        } catch (e: Exception) {
            LogManager.addLog("[ProxyConnector] Error reading response: ${e.message}")
        }

        // Reset timeout for SSH (longer)
        socket.soTimeout = readTimeout

        LogManager.addLog("[ProxyConnector] Direct connection established – ready for SSH")
        return socket
    }

    private fun buildConnectRequest(host: String, port: Int, auth: ProxyAuth?): String {
        val sb = StringBuilder()
        sb.append("CONNECT $host:$port HTTP/1.1\r\n")
        sb.append("Host: $host:$port\r\n")
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
}

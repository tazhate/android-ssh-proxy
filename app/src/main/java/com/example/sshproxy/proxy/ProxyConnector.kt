package com.example.sshproxy.proxy

import com.example.sshproxy.payload.PayloadProcessor
import com.example.sshproxy.LogManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/**
 * Handles HTTP CONNECT proxy connection with optional payload injection.
 * Supports authentication, redirects, and robust error handling.
 */
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
        connectTimeout: Int = 15000,
        readTimeout: Int = 15000,
        followRedirects: Boolean = false,
        splitDelayMs: Long = 500
    ): Socket {
        require(proxyHost.isNotEmpty() && proxyPort in 1..65535) { "Invalid proxy address" }
        require(sshHost.isNotEmpty() && sshPort in 1..65535) { "Invalid SSH target" }

        LogManager.addLog("[ProxyConnector] Connecting to proxy $proxyHost:$proxyPort")

        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(proxyHost, proxyPort), connectTimeout)
            socket.soTimeout = readTimeout
            socket.tcpNoDelay = true
            socket.keepAlive = true
        } catch (e: Exception) {
            throw ProxyConnectionException("Failed to connect to proxy $proxyHost:$proxyPort", e)
        }

        val output = socket.getOutputStream()
        val input = socket.getInputStream()

        // Build CONNECT request
        val connectRequest = buildConnectRequest(sshHost, sshPort, auth)
        LogManager.addLog("[ProxyConnector] Sending CONNECT request to $sshHost:$sshPort")
        output.write(connectRequest.toByteArray())
        output.flush()

        // Read proxy response
        val reader = BufferedReader(InputStreamReader(input))
        val responseLine = reader.readLine() ?: throw ProxyConnectionException("Empty response from proxy")
        LogManager.addLog("[ProxyConnector] Proxy response: $responseLine")

        // Handle redirects if enabled
        if (followRedirects && (responseLine.contains("301") || responseLine.contains("302") ||
                responseLine.contains("303") || responseLine.contains("307"))) {
            val location = extractLocation(reader)
            if (location != null) {
                LogManager.addLog("[ProxyConnector] Following redirect to $location")
                val uri = java.net.URI(location)
                val newHost = uri.host ?: throw ProxyConnectionException("Invalid redirect location")
                val newPort = if (uri.port != -1) uri.port else 80
                socket.close()
                return connectViaProxy(
                    newHost, newPort, sshHost, sshPort, payload, userAgent, auth,
                    connectTimeout, readTimeout, followRedirects, splitDelayMs
                )
            }
        }

        // Check if CONNECT succeeded (2xx, 3xx, 101, or "Connection established")
        val isSuccess = responseLine.startsWith("HTTP/1.1 2") ||
                responseLine.startsWith("HTTP/1.1 3") ||
                responseLine.contains("101") ||
                responseLine.contains("200") ||
                responseLine.contains("Connection established")

        if (!isSuccess) {
            // Drain remaining response for logging
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

        // Inject custom payload if provided
        if (payload.isNotEmpty()) {
            LogManager.addLog("[ProxyConnector] Injecting payload")
            val processedPayload = PayloadProcessor.processPayload(
                payload,
                sshHost,
                sshPort.toString(),
                "$proxyHost:$proxyPort",
                userAgent
            )
            val parts = PayloadProcessor.splitPayload(processedPayload)
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

    private fun buildConnectRequest(host: String, port: Int, auth: ProxyAuth?): String {
        val sb = StringBuilder()
        sb.append("CONNECT $host:$port HTTP/1.1\r\n")
        sb.append("Host: $host:$port\r\n")
        sb.append("User-Agent: Mozilla/5.0\r\n")
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
            if (line!!.startsWith("Location:", ignoreCase = true)) {
                return line.substringAfter(":").trim()
            }
        }
        return null
    }
}
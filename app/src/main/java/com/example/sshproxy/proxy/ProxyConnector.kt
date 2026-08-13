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

        // ---- DIRECT FALLBACK ----
        if (directFallback) {
            LogManager.addLog("[ProxyConnector] Direct fallback mode: connecting to SSH host $sshHost:$sshPort" +
                    if (sslForSSH) " (SSL)" else "")
            return connectDirect(
                sshHost, sshPort, proxyHost, proxyPort, payload, userAgent,
                connectTimeout, splitDelayMs, sslForSSH, usePayload
            )
        }

        // ---- NORMAL PROXY MODE ----
        val targetHost = proxyHost
        val targetPort = proxyPort

        LogManager.addLog("[ProxyConnector] Connecting to proxy $targetHost:$targetPort" +
                if (sslForProxy) " (SSL)" else "")

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

        // ---- CONNECT request with proxy Host header ----
        val connectRequest = buildConnectRequest(sshHost, sshPort, proxyHost, proxyPort, auth)
        LogManager.addLog("[ProxyConnector] Sending CONNECT request:\n$connectRequest")
        output.write(connectRequest.toByteArray())
        output.flush()

        val reader = BufferedReader(InputStreamReader(input))
        var responseLine = reader.readLine() ?: throw ProxyConnectionException("Empty response from proxy")
        LogManager.addLog("[ProxyConnector] Proxy response: $responseLine")

        // ---- Handle redirects ----
        if (responseLine.contains("301") || responseLine.contains("302") ||
            responseLine.contains("303") || responseLine.contains("307")) {

            if (followRedirects) {
                val location = extractLocation(reader)
                if (location != null) {
                    LogManager.addLog("[ProxyConnector] Following redirect to $location")
                    socket.close()
                    try {
                        val uri = URI(location)
                        val newHost = uri.host ?: throw ProxyConnectionException("Invalid redirect location")
                        val newPort = if (uri.port != -1) uri.port else 80
                        return connectViaProxy(
                            newHost, newPort, sshHost, sshPort, payload, userAgent, auth,
                            connectTimeout, readTimeout, false,
                            splitDelayMs, sslForProxy, sslForSSH, directFallback, usePayload
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

        // ---- Check success ----
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

        drainHttpHeaders(reader)

        // ---- Inject payload ----
        if (usePayload && payload.isNotEmpty()) {
            LogManager.addLog("[ProxyConnector] Injecting payload (usePayload=true)")
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
            LogManager.addLog("[ProxyConnector] Payload injected (${parts.size} parts)")
        } else {
            LogManager.addLog("[ProxyConnector] Skipping payload injection (usePayload=false)")
        }

        LogManager.addLog("[ProxyConnector] Tunnel established successfully")
        return socket
    }

    // ---- DIRECT FALLBACK ----
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

    private fun buildConnectRequest(sshHost: String, sshPort: Int, proxyHost: String, proxyPort: Int, auth: ProxyAuth?): String {
        val sb = StringBuilder()
        sb.append("CONNECT $sshHost:$sshPort HTTP/1.1\r\n")
        sb.append("Host: $proxyHost:$proxyPort\r\n")   // Use proxy host in Host header
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

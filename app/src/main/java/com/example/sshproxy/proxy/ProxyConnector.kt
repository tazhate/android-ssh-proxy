package com.example.sshproxy.proxy

import com.example.sshproxy.LogManager
import com.example.sshproxy.payload.PayloadProcessor
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

class ProxyConnector {

    fun establishTunnel(
        proxyHost: String,
        proxyPort: Int,
        sshHost: String,
        sshPort: Int,
        payload: String,
        userAgent: String,
        connectTimeout: Int = 25000,
        readTimeout: Int = 5000,
        followRedirects: Boolean = true,
        splitDelayMs: Long = 500,
        useSsl: Boolean = false,
        usePayload: Boolean = true,
        useEnhanced: Boolean = false
    ): Socket {

        val socket = Socket()
        socket.soTimeout = readTimeout
        socket.connect(java.net.InetSocketAddress(proxyHost, proxyPort), connectTimeout)

        val output = socket.getOutputStream()
        val input = socket.getInputStream()

        if (!usePayload) {
            sendConnectRequest(output, sshHost, sshPort)
            readConnectResponse(input)
            return socket
        }

        // Process payload using your actual PayloadProcessor
        val host = sshHost
        val port = sshPort.toString()
        val proxy = proxyHost
        val processedPayload = PayloadProcessor.processPayload(payload, host, port, proxy, userAgent)

        if (useEnhanced) {
            // Split using the actual splitPayload method
            val parts = PayloadProcessor.splitPayload(processedPayload)
            LogManager.addLog("[ProxyConnector] Enhanced: ${parts.size} parts")

            for ((index, part) in parts.withIndex()) {
                if (part.isBlank()) continue
                var toSend = part.trim()
                if (!toSend.endsWith("\r\n\r\n")) toSend += "\r\n\r\n"
                output.write(toSend.toByteArray())
                output.flush()
                LogManager.addLog("[ProxyConnector] Sent part ${index+1}/${parts.size}")

                val response = readFullResponse(input)
                LogManager.addLog("[ProxyConnector] Response for part ${index+1}: ${response.take(200)}")

                val status = extractStatus(response)
                if (status !in listOf(200, 101, 403, 302, 100)) {
                    throw ProxyConnectionException("Unexpected status $status for part ${index+1}")
                }

                if (index < parts.size - 1) {
                    Thread.sleep(splitDelayMs)
                }
            }
        } else {
            // Standard mode: send the whole processed payload
            output.write(processedPayload.toByteArray())
            output.flush()
            LogManager.addLog("[ProxyConnector] Payload sent (1 part)")

            val response = readFullResponse(input)
            val status = extractStatus(response)
            if (status !in listOf(200, 101, 403, 302)) {
                throw ProxyConnectionException("Unexpected status $status")
            }
        }

        return socket
    }

    private fun sendConnectRequest(output: java.io.OutputStream, sshHost: String, sshPort: Int) {
        val request = "CONNECT $sshHost:$sshPort HTTP/1.1\r\nHost: $sshHost:$sshPort\r\nConnection: keep-alive\r\n\r\n"
        output.write(request.toByteArray())
        output.flush()
    }

    private fun readConnectResponse(input: java.io.InputStream): String {
        val reader = BufferedReader(InputStreamReader(input))
        val firstLine = reader.readLine() ?: throw ProxyConnectionException("No response")
        if (!firstLine.contains("200")) {
            throw ProxyConnectionException("CONNECT failed: $firstLine")
        }
        while (reader.readLine()?.isNotEmpty() == true) {}
        return firstLine
    }

    private fun readFullResponse(input: java.io.InputStream): String {
        val reader = BufferedReader(InputStreamReader(input))
        val response = StringBuilder()
        var line: String? = reader.readLine()
        while (line != null && line.isNotEmpty()) {
            response.append(line).append("\n")
            line = reader.readLine()
        }
        return response.toString()
    }

    private fun extractStatus(response: String): Int {
        val firstLine = response.lines().firstOrNull() ?: return 0
        val parts = firstLine.split(" ")
        return if (parts.size >= 2) parts[1].toIntOrNull() ?: 0 else 0
    }
}
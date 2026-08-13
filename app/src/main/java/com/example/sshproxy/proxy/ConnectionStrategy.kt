package com.example.sshproxy.proxy

import com.example.sshproxy.LogManager
import java.net.Socket

class ConnectionStrategy {

    private val connector = ProxyConnector()

    suspend fun establishTunnel(
        proxyHost: String,
        proxyPort: Int,
        sshHost: String,
        sshPort: Int,
        payload: String,
        userAgent: String = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36",
        auth: ProxyAuth? = null,
        connectTimeout: Int = 25000,
        readTimeout: Int = 5000,
        followRedirects: Boolean = false,
        splitDelayMs: Long = 500,
        useSsl: Boolean = false
    ): Socket {
        LogManager.addLog("[Strategy] Starting connection attempt...")
        LogManager.addLog("[Strategy] Proxy: $proxyHost:$proxyPort, SSH: $sshHost:$sshPort")

        try {
            LogManager.addLog("[Strategy] Attempt 1: Real proxy CONNECT")
            val result = connector.connectViaProxy(
                proxyHost = proxyHost,
                proxyPort = proxyPort,
                sshHost = sshHost,
                sshPort = sshPort,
                payload = payload,
                userAgent = userAgent,
                auth = auth,
                connectTimeout = connectTimeout,
                readTimeout = readTimeout,
                followRedirects = followRedirects,
                splitDelayMs = splitDelayMs,
                useSsl = useSsl,
                directFallback = false
            )
            LogManager.addLog("[Strategy] ✅ Proxy CONNECT succeeded")
            return result
        } catch (e: ProxyConnectionException) {
            val msg = e.message ?: ""
            LogManager.addLog("[Strategy] ⚠️ Proxy CONNECT failed: $msg")
            if (msg.contains("302") || msg.contains("redirect") ||
                msg.contains("400") || msg.contains("403") ||
                msg.contains("timeout") || msg.contains("Bad Request")) {
                LogManager.addLog("[Strategy] Falling back to direct spoofing...")
                return tryDirectSpoof(
                    proxyHost, proxyPort, sshHost, sshPort,
                    payload, userAgent, connectTimeout, readTimeout, splitDelayMs, useSsl
                )
            } else {
                throw e
            }
        }
    }

    private suspend fun tryDirectSpoof(
        proxyHost: String,
        proxyPort: Int,
        sshHost: String,
        sshPort: Int,
        payload: String,
        userAgent: String,
        connectTimeout: Int,
        readTimeout: Int,
        splitDelayMs: Long,
        useSsl: Boolean
    ): Socket {
        LogManager.addLog("[Strategy] Direct spoofing to $sshHost:$sshPort")
        return connector.connectViaProxy(
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            sshHost = sshHost,
            sshPort = sshPort,
            payload = payload,
            userAgent = userAgent,
            auth = null,
            connectTimeout = connectTimeout,
            readTimeout = readTimeout,
            followRedirects = false,
            splitDelayMs = splitDelayMs,
            useSsl = useSsl,
            directFallback = true
        )
    }
}

package com.example.sshproxy.proxy

import com.example.sshproxy.LogManager
import java.net.Socket

/**
 * Automatic connection strategy engine.
 * Tries proxy CONNECT first, then falls back to direct spoofing if needed.
 */
class ConnectionStrategy {

    private val connector = ProxyConnector()

    /**
     * Attempts to establish a tunnel using the best strategy.
     * @return A connected Socket ready for SSH.
     */
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

        // ---- Strategy 1: Try real proxy CONNECT ----
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

            // If the proxy returned a redirect (302) or a 400/403/timeout, try spoofing.
            if (msg.contains("302") || msg.contains("redirect") ||
                msg.contains("400") || msg.contains("403") ||
                msg.contains("timeout") || msg.contains("Bad Request")) {
                LogManager.addLog("[Strategy] Falling back to direct spoofing...")
                return tryDirectSpoof(
                    proxyHost, proxyPort, sshHost, sshPort,
                    payload, userAgent, connectTimeout, readTimeout, splitDelayMs, useSsl
                )
            } else {
                // Other error – rethrow
                throw e
            }
        }
    }

    /**
     * Direct spoofing: connect to SSH host, send payload (no CONNECT).
     */
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
            directFallback = true  // This tells ProxyConnector to skip CONNECT
        )
    }
}

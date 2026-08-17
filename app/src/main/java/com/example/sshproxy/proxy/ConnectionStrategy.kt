package com.example.sshproxy.proxy

import com.example.sshproxy.LogManager
import java.net.Socket

class ConnectionStrategy {

    fun establishTunnel(
        proxyHost: String,
        proxyPort: Int,
        sshHost: String,
        sshPort: Int,
        payload: String,
        userAgent: String,
        auth: ProxyAuth? = null,
        connectTimeout: Int = 25000,
        readTimeout: Int = 5000,
        followRedirects: Boolean = true,
        splitDelayMs: Long = 500,
        useSsl: Boolean = false,
        usePayload: Boolean = true,
        useEnhanced: Boolean = false
    ): Socket {
        val connector = ProxyConnector()

        // Try direct payload mode first (if usePayload)
        if (usePayload) {
            try {
                return connector.establishTunnel(
                    proxyHost = proxyHost,
                    proxyPort = proxyPort,
                    sshHost = sshHost,
                    sshPort = sshPort,
                    payload = payload,
                    userAgent = userAgent,
                    connectTimeout = connectTimeout,
                    readTimeout = readTimeout,
                    followRedirects = followRedirects,
                    splitDelayMs = splitDelayMs,
                    useSsl = useSsl,
                    usePayload = true,
                    useEnhanced = useEnhanced
                )
            } catch (e: ProxyConnectionException) {
                LogManager.addLog("[Strategy] Payload mode failed: ${e.message}")
            }
        }

        // Fallback: direct CONNECT to SSH host
        LogManager.addLog("[Strategy] Falling back to direct CONNECT")
        return connector.establishTunnel(
            proxyHost = sshHost,
            proxyPort = sshPort,
            sshHost = sshHost,
            sshPort = sshPort,
            payload = "",
            userAgent = userAgent,
            connectTimeout = connectTimeout,
            readTimeout = readTimeout,
            followRedirects = followRedirects,
            splitDelayMs = splitDelayMs,
            useSsl = useSsl,
            usePayload = false,
            useEnhanced = false
        )
    }
}
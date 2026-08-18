package com.example.sshproxy.proxy

import com.example.sshproxy.LogManager
import java.net.Socket

class ConnectionStrategy {

    suspend fun establishTunnel(
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
        useEnhanced: Boolean = false   // <-- NEW
    ): Socket {
        val connector = ProxyConnector()

        // Try proxy mode first
        try {
            return connector.connectViaProxy(
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
                sslForProxy = useSsl,
                sslForSSH = useSsl,
                directFallback = false,
                usePayload = usePayload,
                useEnhanced = useEnhanced
            )
        } catch (e: ProxyConnectionException) {
            LogManager.addLog("[Strategy] Proxy mode failed: ${e.message}")
        }

        // Fallback: direct spoofing
        LogManager.addLog("[Strategy] Falling back to direct spoofing")
        return connector.connectViaProxy(
            proxyHost = sshHost,
            proxyPort = sshPort,
            sshHost = sshHost,
            sshPort = sshPort,
            payload = payload,
            userAgent = userAgent,
            auth = auth,
            connectTimeout = connectTimeout,
            readTimeout = readTimeout,
            followRedirects = followRedirects,
            splitDelayMs = splitDelayMs,
            sslForProxy = false,
            sslForSSH = useSsl,
            directFallback = true,
            usePayload = usePayload,
            useEnhanced = useEnhanced
        )
    }
}
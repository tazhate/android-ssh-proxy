package com.example.sshproxy.data

import android.content.Context
import com.example.sshproxy.R
import com.example.sshproxy.network.ConnectionQuality
import com.example.sshproxy.network.PingResult
import com.example.sshproxy.network.ServerStats

data class ConnectionStatus(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val server: Server? = null,
    val connectedSince: Long? = null,
    val latestPing: PingResult? = null,
    val serverStats: ServerStats? = null,
    val connectionQuality: ConnectionQuality = ConnectionQuality.UNKNOWN,
    val errorMessage: String? = null,
    val reconnectionAttempt: Int = 0,
    val maxReconnectionAttempts: Int = 0,
    val isReconnecting: Boolean = false
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING, 
    CONNECTED,
    DISCONNECTING,
    RECONNECTING,
    ERROR
}

// Extension functions for display
fun ConnectionStatus.getDisplayStatus(context: Context): String {
    return when (state) {
        ConnectionState.DISCONNECTED -> context.getString(R.string.connection_status_disconnected)
        ConnectionState.CONNECTING -> context.getString(R.string.connection_status_connecting)
        ConnectionState.CONNECTED -> context.getString(R.string.connection_status_connected)
        ConnectionState.DISCONNECTING -> context.getString(R.string.connection_status_disconnecting)
        ConnectionState.RECONNECTING -> context.getString(R.string.connection_status_reconnecting, reconnectionAttempt, maxReconnectionAttempts)
        ConnectionState.ERROR -> context.getString(R.string.connection_status_error)
    }
}

fun ConnectionStatus.getConnectionDuration(): String? {
    if (state != ConnectionState.CONNECTED || connectedSince == null) {
        return null
    }
    
    val durationMs = System.currentTimeMillis() - connectedSince
    val seconds = durationMs / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    
    return when {
        days > 0 -> "${days}д ${hours % 24}ч"
        hours > 0 -> "${hours}ч ${minutes % 60}м"
        minutes > 0 -> "${minutes}м ${seconds % 60}с"
        else -> "${seconds}с"
    }
}

fun ConnectionStatus.getPingDisplay(context: Context): String? {
    return when {
        latestPing == null -> null
        !latestPing.isSuccessful -> context.getString(R.string.connection_ping_no_response)
        else -> "${latestPing.latencyMs}мс"
    }
}

fun ConnectionStatus.getQualityColor(): Int {
    return connectionQuality.color
}
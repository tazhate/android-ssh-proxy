package com.example.sshproxy.data

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
fun ConnectionStatus.getDisplayStatus(): String {
    return when (state) {
        ConnectionState.DISCONNECTED -> "Отключено"
        ConnectionState.CONNECTING -> "Подключение..."
        ConnectionState.CONNECTED -> "Подключено"
        ConnectionState.DISCONNECTING -> "Отключение..."
        ConnectionState.RECONNECTING -> "Переподключение... ($reconnectionAttempt/$maxReconnectionAttempts)"
        ConnectionState.ERROR -> "Ошибка"
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

fun ConnectionStatus.getPingDisplay(): String? {
    return when {
        latestPing == null -> null
        !latestPing.isSuccessful -> "Нет ответа"
        else -> "${latestPing.latencyMs}мс"
    }
}

fun ConnectionStatus.getQualityColor(): Int {
    return connectionQuality.color
}
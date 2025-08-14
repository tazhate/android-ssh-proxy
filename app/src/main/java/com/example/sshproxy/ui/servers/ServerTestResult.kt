package com.example.sshproxy.ui.servers

import com.example.sshproxy.network.ConnectionQuality

data class ServerTestResult(
    val serverId: Long,
    val latencyMs: Long = 0,
    val quality: ConnectionQuality = ConnectionQuality.UNKNOWN,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null,
    val isLoading: Boolean = false
) {
    companion object {
        fun loading(serverId: Long) = ServerTestResult(serverId = serverId, isLoading = true)
        
        fun success(serverId: Long, latencyMs: Long, quality: ConnectionQuality) = 
            ServerTestResult(serverId = serverId, latencyMs = latencyMs, quality = quality, isSuccess = true)
            
        fun failure(serverId: Long, errorMessage: String) = 
            ServerTestResult(serverId = serverId, errorMessage = errorMessage, isSuccess = false)
    }
}
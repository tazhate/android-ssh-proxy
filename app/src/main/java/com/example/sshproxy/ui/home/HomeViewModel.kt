package com.example.sshproxy.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sshproxy.SshProxyService
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.data.Server
import com.example.sshproxy.data.ServerRepository
import com.example.sshproxy.data.ConnectionStatus
import com.example.sshproxy.data.ConnectionState
import com.example.sshproxy.network.ConnectionQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class HomeViewModel(
    private val serverRepository: ServerRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private val _selectedServer = MutableStateFlow<Server?>(null)
    val selectedServer: StateFlow<Server?> = _selectedServer.asStateFlow()

    val isRunning: StateFlow<Boolean> = SshProxyService.isRunning
    
    // Combine connection state with current server for full status
    private val _connectionStatus = MutableStateFlow(ConnectionStatus())
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    init {
        viewModelScope.launch {
            serverRepository.getAllServers().collect { serverList ->
                _servers.value = serverList
                val activeServerId = preferencesManager.getActiveServerId()
                val currentSelection = _selectedServer.value

                if (currentSelection == null || serverList.find { it.id == currentSelection.id } == null) {
                    _selectedServer.value = serverList.find { it.id == activeServerId } ?: serverList.firstOrNull()
                }
            }
        }
        
        // Monitor connection state changes
        viewModelScope.launch {
            combine(
                SshProxyService.connectionState,
                SshProxyService.connectionStartTime,
                _selectedServer
            ) { connectionState, connectedSince, server ->
                val newState = when (connectionState) {
                    SshProxyService.ConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTED
                    SshProxyService.ConnectionState.CONNECTING -> ConnectionState.CONNECTING
                    SshProxyService.ConnectionState.CONNECTED -> ConnectionState.CONNECTED
                    SshProxyService.ConnectionState.DISCONNECTING -> ConnectionState.DISCONNECTING
                }
                
                _connectionStatus.value = _connectionStatus.value.copy(
                    state = newState,
                    server = server,
                    connectedSince = connectedSince
                )
            }.collect { }
        }
        
        // Monitor ping data
        viewModelScope.launch {
            SshProxyService.currentPingMonitor.collect { pingMonitor ->
                if (pingMonitor != null) {
                    // Collect ping results
                    launch {
                        pingMonitor.latestPing.collect { pingResult ->
                            val serverStats = pingMonitor.serverStats.value
                            val quality = pingMonitor.getConnectionQuality()
                            updatePingStats(pingResult, serverStats, quality)
                        }
                    }
                }
            }
        }
    }

    fun selectServer(server: Server) {
        _selectedServer.value = server
        preferencesManager.setActiveServerId(server.id)
    }
    
    fun updatePingStats(pingResult: com.example.sshproxy.network.PingResult?, serverStats: com.example.sshproxy.network.ServerStats?, quality: ConnectionQuality) {
        _connectionStatus.value = _connectionStatus.value.copy(
            latestPing = pingResult,
            serverStats = serverStats,
            connectionQuality = quality
        )
    }
    
    fun updateConnectionError(error: String) {
        _connectionStatus.value = _connectionStatus.value.copy(
            state = ConnectionState.ERROR,
            errorMessage = error
        )
    }
    
    fun updateReconnectionStatus(isReconnecting: Boolean, attempt: Int, maxAttempts: Int) {
        _connectionStatus.value = _connectionStatus.value.copy(
            isReconnecting = isReconnecting,
            reconnectionAttempt = attempt,
            maxReconnectionAttempts = maxAttempts,
            state = if (isReconnecting) ConnectionState.RECONNECTING else _connectionStatus.value.state
        )
    }
}

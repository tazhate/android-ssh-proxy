package com.example.sshproxy.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sshproxy.SshProxyService
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.data.Server
import com.example.sshproxy.data.ServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    }

    fun selectServer(server: Server) {
        _selectedServer.value = server
        preferencesManager.setActiveServerId(server.id)
    }
}

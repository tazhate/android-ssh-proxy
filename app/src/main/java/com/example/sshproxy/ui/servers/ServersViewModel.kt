package com.example.sshproxy.ui.servers

import androidx.lifecycle.*
import com.example.sshproxy.data.Server
import com.example.sshproxy.data.ServerRepository
import com.example.sshproxy.data.PreferencesManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ServersViewModel(private val repository: ServerRepository, private val preferencesManager: PreferencesManager) : ViewModel() {
    val servers: StateFlow<List<Server>> = repository.getAllServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun insertServer(server: Server) = viewModelScope.launch {
        repository.insertServer(server)
        val serverList = repository.getAllServers().first()
        if (serverList.size == 1) {
            preferencesManager.setActiveServerId(serverList.first().id)
        }
    }

    fun deleteServer(server: Server) = viewModelScope.launch {
        repository.deleteServer(server)
        val serverList = repository.getAllServers().first()
        if (serverList.size == 1) {
            preferencesManager.setActiveServerId(serverList.first().id)
        } else if (serverList.isEmpty()) {
            preferencesManager.setActiveServerId(-1)
        }
    }
}

class ServersViewModelFactory(private val repository: ServerRepository, private val preferencesManager: PreferencesManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ServersViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ServersViewModel(repository, preferencesManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
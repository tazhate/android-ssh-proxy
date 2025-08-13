package com.example.sshproxy.ui.servers

import androidx.lifecycle.*
import com.example.sshproxy.data.Server
import com.example.sshproxy.data.ServerRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ServersViewModel(private val repository: ServerRepository) : ViewModel() {
    val servers: StateFlow<List<Server>> = repository.getAllServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun insertServer(server: Server) = viewModelScope.launch {
        repository.insertServer(server)
    }

    fun deleteServer(server: Server) = viewModelScope.launch {
        repository.deleteServer(server)
    }
}

class ServersViewModelFactory(private val repository: ServerRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ServersViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ServersViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

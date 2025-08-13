package com.example.sshproxy.ui.instructions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sshproxy.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class InstructionsViewModel(
    private val keyRepository: KeyRepository,
    private val serverRepository: ServerRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _keys = MutableStateFlow<List<SshKey>>(emptyList())
    val keys: StateFlow<List<SshKey>> = _keys.asStateFlow()

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private val _selectedKey = MutableStateFlow<SshKey?>(null)
    val selectedKey: StateFlow<SshKey?> = _selectedKey.asStateFlow()

    private val _selectedServer = MutableStateFlow<Server?>(null)
    val selectedServer: StateFlow<Server?> = _selectedServer.asStateFlow()

    init {
        viewModelScope.launch {
            keyRepository.getAllKeys().collect { keyList ->
                _keys.value = keyList
                val activeKeyId = preferencesManager.getActiveKeyId()
                // If no key is selected, or the selected one was deleted, select a new one.
                if (_selectedKey.value == null || keyList.find { it.id == _selectedKey.value?.id } == null) {
                    val newKeyToSelect = keyList.find { it.id == activeKeyId } ?: keyList.firstOrNull()
                    _selectedKey.value = newKeyToSelect
                    // Also update the preference if we made a new selection
                    newKeyToSelect?.let { preferencesManager.setActiveKeyId(it.id) }
                }
            }
        }

        viewModelScope.launch {
            serverRepository.getAllServers().collect { serverList ->
                _servers.value = serverList
                val activeServerId = preferencesManager.getActiveServerId()
                // If no server is selected, or the selected one was deleted, select a new one.
                if (_selectedServer.value == null || serverList.find { it.id == _selectedServer.value?.id } == null) {
                    val newServerToSelect = serverList.find { it.id == activeServerId } ?: serverList.firstOrNull()
                    _selectedServer.value = newServerToSelect
                    // Also update the preference if we made a new selection
                    newServerToSelect?.let { preferencesManager.setActiveServerId(it.id) }
                }
            }
        }
    }

    fun selectKey(key: SshKey) {
        _selectedKey.value = key
        preferencesManager.setActiveKeyId(key.id)
    }

    fun selectServer(server: Server) {
        _selectedServer.value = server
        preferencesManager.setActiveServerId(server.id)
    }
}

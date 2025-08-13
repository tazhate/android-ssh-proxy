package com.example.sshproxy.ui.instructions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.sshproxy.data.KeyRepository
import com.example.sshproxy.data.PreferencesManager
import com.example.sshproxy.data.ServerRepository

class InstructionsViewModelFactory(
    private val keyRepository: KeyRepository,
    private val serverRepository: ServerRepository,
    private val preferencesManager: PreferencesManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(InstructionsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return InstructionsViewModel(keyRepository, serverRepository, preferencesManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

package com.example.sshproxy

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.*

object AppLog {
    private val _logMessages = MutableLiveData<List<String>>(emptyList())
    val logMessages: LiveData<List<String>> = _logMessages

    private const val MAX_LOG_LINES = 100

    fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "$timestamp: $message"

        val currentLogs = _logMessages.value?.toMutableList() ?: mutableListOf()
        currentLogs.add(0, logEntry) // Добавляем новое сообщение в начало списка

        if (currentLogs.size > MAX_LOG_LINES) {
            currentLogs.removeAt(currentLogs.size - 1) // Удаляем самое старое
        }

        _logMessages.postValue(currentLogs)
    }

    fun clear() {
        _logMessages.postValue(emptyList())
    }
}

package com.example.sshproxy

import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.*

object LogManager {
    private val _logs = MutableLiveData<MutableList<String>>(mutableListOf())
    val logs: MutableLiveData<MutableList<String>> = _logs
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun addLog(message: String) {
        val timestamp = dateFormat.format(Date())
        val entry = "[$timestamp] $message"
        val currentList = _logs.value ?: mutableListOf()
        currentList.add(entry)
        _logs.postValue(currentList)
    }

    fun clearLogs() {
        _logs.postValue(mutableListOf())
    }
}

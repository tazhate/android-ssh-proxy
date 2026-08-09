package com.example.sshproxy

import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.*

object LogManager {
    private val _logs = MutableLiveData<List<String>>(emptyList())
    val logs: MutableLiveData<List<String>> = _logs
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun addLog(message: String) {
        val timestamp = dateFormat.format(Date())
        val entry = "[$timestamp] $message"
        val currentList = _logs.value?.toMutableList() ?: mutableListOf()
        currentList.add(entry)
        _logs.postValue(currentList)
        // Also print to Android log so ADB shows it
        android.util.Log.d("LogManager", entry)
    }

    fun clearLogs() {
        _logs.postValue(emptyList())
    }
}

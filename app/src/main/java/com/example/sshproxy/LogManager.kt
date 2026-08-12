package com.example.sshproxy

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object LogManager {
    private const val MAX_LOG_LINES = 150
    private val logs = CopyOnWriteArrayList<String>()
    private var listeners = mutableListOf<LogUpdateListener>()

    interface LogUpdateListener {
        fun onLogUpdated()
    }

    fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$timestamp] $message"
        logs.add(entry)
        if (logs.size > MAX_LOG_LINES) {
            val toRemove = logs.size - MAX_LOG_LINES
            repeat(toRemove) {
                if (logs.isNotEmpty()) logs.removeAt(0)
            }
        }
        notifyListeners()
    }

    fun getLogs(): List<String> = logs

    fun clearLogs() {
        logs.clear()
        notifyListeners()
    }

    fun registerListener(listener: LogUpdateListener) {
        listeners.add(listener)
    }

    fun unregisterListener(listener: LogUpdateListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            try { listener.onLogUpdated() } catch (_: Exception) {}
        }
    }
}
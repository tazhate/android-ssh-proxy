package com.example.sshproxy.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ssh_proxy_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ACTIVE_SERVER_ID = "active_server_id"
        private const val KEY_ACTIVE_KEY_ID = "active_key_id"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_HEALTH_CHECK_INTERVAL = "health_check_interval"
        private const val KEY_MAX_RECONNECT_ATTEMPTS = "max_reconnect_attempts"
        private const val KEY_INITIAL_BACKOFF_MS = "initial_backoff_ms"
        private const val KEY_MAX_BACKOFF_MS = "max_backoff_ms"
        private const val KEY_THEME = "theme"
        private const val KEY_LANGUAGE = "language"
    }

    // Active Server ID
    fun getActiveServerId(): Long? {
        val id = prefs.getLong(KEY_ACTIVE_SERVER_ID, -1)
        return if (id == -1L) null else id
    }

    fun setActiveServerId(serverId: Long) {
        prefs.edit().putLong(KEY_ACTIVE_SERVER_ID, serverId).apply()
    }

    // Active Key ID
    fun setActiveKeyId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_KEY_ID, id).apply()
    }

    fun getActiveKeyId(): String? {
        return prefs.getString(KEY_ACTIVE_KEY_ID, null)
    }

    // Auto-reconnection settings
    fun isAutoReconnectEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_RECONNECT, true)
    }

    fun setAutoReconnectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply()
    }

    fun getHealthCheckInterval(): Long {
        return prefs.getLong(KEY_HEALTH_CHECK_INTERVAL, 30_000)
    }

    fun setHealthCheckInterval(intervalMs: Long) {
        prefs.edit().putLong(KEY_HEALTH_CHECK_INTERVAL, intervalMs).apply()
    }

    fun getMaxReconnectAttempts(): Int {
        return prefs.getInt(KEY_MAX_RECONNECT_ATTEMPTS, 10)
    }

    fun setMaxReconnectAttempts(attempts: Int) {
        prefs.edit().putInt(KEY_MAX_RECONNECT_ATTEMPTS, attempts).apply()
    }

    fun getInitialBackoffMs(): Long {
        return prefs.getLong(KEY_INITIAL_BACKOFF_MS, 1_000)
    }

    fun setInitialBackoffMs(backoffMs: Long) {
        prefs.edit().putLong(KEY_INITIAL_BACKOFF_MS, backoffMs).apply()
    }

    fun getMaxBackoffMs(): Long {
        return prefs.getLong(KEY_MAX_BACKOFF_MS, 300_000)
    }

    fun setMaxBackoffMs(backoffMs: Long) {
        prefs.edit().putLong(KEY_MAX_BACKOFF_MS, backoffMs).apply()
    }

    // Theme
    fun getTheme(): String {
        return prefs.getString(KEY_THEME, "system") ?: "system"
    }

    fun setTheme(theme: String) {
        prefs.edit().putString(KEY_THEME, theme).apply()
    }

    // Language
    fun getLanguage(): String {
        return prefs.getString(KEY_LANGUAGE, "system") ?: "system"
    }

    fun setLanguage(language: String) {
        prefs.edit().putString(KEY_LANGUAGE, language).apply()
    }
}
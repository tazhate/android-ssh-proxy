package com.example.sshproxy.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ACTIVE_SERVER_ID = "active_server_id"
        private const val KEY_ACTIVE_KEY_ID = "active_key_id"
        private const val TAG = "PreferencesManager"

        // Auto-reconnection settings
        private const val KEY_AUTO_RECONNECT_ENABLED = "auto_reconnect_enabled"
        private const val KEY_HEALTH_CHECK_INTERVAL = "health_check_interval_ms"
        private const val KEY_MAX_RECONNECT_ATTEMPTS = "max_reconnect_attempts"
        private const val KEY_INITIAL_BACKOFF_MS = "initial_backoff_ms"
        private const val KEY_MAX_BACKOFF_MS = "max_backoff_ms"

        // Default values
        private const val DEFAULT_AUTO_RECONNECT = true
        private const val DEFAULT_HEALTH_CHECK_INTERVAL = 30_000L // 30 seconds
        private const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 10
        private const val DEFAULT_INITIAL_BACKOFF = 1_000L // 1 second
        private const val DEFAULT_MAX_BACKOFF = 300_000L // 5 minutes
    }

    // Active Server ID
    fun getActiveServerId(): Long {
        return try {
            prefs.getLong(KEY_ACTIVE_SERVER_ID, -1L)
        } catch (e: ClassCastException) {
            Log.w(TAG, "Could not get active server ID as Long, attempting fallback.", e)
            // Fallback for old string-based IDs
            prefs.getString(KEY_ACTIVE_SERVER_ID, null)?.toLongOrNull() ?: -1L
        }
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
        return prefs.getBoolean(KEY_AUTO_RECONNECT_ENABLED, DEFAULT_AUTO_RECONNECT)
    }

    fun setAutoReconnectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT_ENABLED, enabled).apply()
    }

    fun getHealthCheckInterval(): Long {
        return prefs.getLong(KEY_HEALTH_CHECK_INTERVAL, DEFAULT_HEALTH_CHECK_INTERVAL)
    }

    fun setHealthCheckInterval(intervalMs: Long) {
        prefs.edit().putLong(KEY_HEALTH_CHECK_INTERVAL, intervalMs).apply()
    }

    fun getMaxReconnectAttempts(): Int {
        return prefs.getInt(KEY_MAX_RECONNECT_ATTEMPTS, DEFAULT_MAX_RECONNECT_ATTEMPTS)
    }

    fun setMaxReconnectAttempts(attempts: Int) {
        prefs.edit().putInt(KEY_MAX_RECONNECT_ATTEMPTS, attempts).apply()
    }

    fun getInitialBackoffMs(): Long {
        return prefs.getLong(KEY_INITIAL_BACKOFF_MS, DEFAULT_INITIAL_BACKOFF)
    }

    fun setInitialBackoffMs(backoffMs: Long) {
        prefs.edit().putLong(KEY_INITIAL_BACKOFF_MS, backoffMs).apply()
    }

    fun getMaxBackoffMs(): Long {
        return prefs.getLong(KEY_MAX_BACKOFF_MS, DEFAULT_MAX_BACKOFF)
    }

    fun setMaxBackoffMs(backoffMs: Long) {
        prefs.edit().putLong(KEY_MAX_BACKOFF_MS, backoffMs).apply()
    }
}

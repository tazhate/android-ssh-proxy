package com.example.sshproxy.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)

    fun getActiveKeyId(): String? {
        return prefs.getString("active_key_id", null)
    }

    fun setActiveKeyId(keyId: String) {
        prefs.edit().putString("active_key_id", keyId).apply()
    }

    fun getActiveServerId(): String? {
        return prefs.getString("active_server_id", null)
    }

    fun setActiveServerId(serverId: String) {
        prefs.edit().putString("active_server_id", serverId).apply()
    }
}

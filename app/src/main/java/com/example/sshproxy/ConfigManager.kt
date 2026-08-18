package com.example.sshproxy

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ConfigManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gtunnel_configs", Context.MODE_PRIVATE)
    private val gson = Gson()

    data class TunnelConfig(
        val sshDetails: String,
        val proxyInput: String,
        val payload: String,
        val splitDelay: Int,
        val dnsPrimary: String,
        val dnsSecondary: String,
        val pingTarget: String,
        val enableCompression: Boolean,
        val alwaysReconnect: Boolean,
        val followRedirects: Boolean,
        val mtu: Int,
        val sendBuffer: Int,
        val receiveBuffer: Int,
        val pingUrl: String,
        val pingInterval: Int,
        val pingTimeout: Int,
        val usePayload: Boolean = true,
        val proxySsl: Boolean = false
    )

    fun saveConfig(config: TunnelConfig) {
        val json = gson.toJson(config)
        prefs.edit().putString("current_config", json).apply()
    }

    fun loadConfig(): TunnelConfig? {
        val json = prefs.getString("current_config", null)
        return if (json != null) {
            try {
                gson.fromJson(json, TunnelConfig::class.java)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    fun validateConfig(config: TunnelConfig): Boolean {
        if (config.sshDetails.isEmpty()) return false
        if (!config.sshDetails.contains("@") || !config.sshDetails.contains(":")) return false
        if (config.mtu !in 1280..9000) return false
        if (config.splitDelay < 0) return false
        return true
    }

    fun saveConfigList(configs: List<TunnelConfig>) {
        val json = gson.toJson(configs)
        prefs.edit().putString("config_list", json).apply()
    }

    fun loadConfigList(): List<TunnelConfig> {
        val json = prefs.getString("config_list", null)
        return if (json != null) {
            try {
                val type = object : TypeToken<List<TunnelConfig>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                emptyList()
            }
        } else emptyList()
    }
}

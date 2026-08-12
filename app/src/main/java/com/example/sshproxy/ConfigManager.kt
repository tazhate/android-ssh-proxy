package com.example.sshproxy

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages config persistence and validation.
 * Uses SharedPreferences with Gson for serialization.
 */
class ConfigManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gtunnel_configs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Data class for a complete tunnel config (matches UI fields)
    data class TunnelConfig(
        val sshDetails: String,       // host:port@user:pass
        val proxyInput: String,       // host:port or Base64
        val payload: String,
        val splitDelay: Int,
        val dnsServer: String,
        val pingTarget: String,
        val enableCompression: Boolean,
        val alwaysReconnect: Boolean,
        val followRedirects: Boolean,
        val mtu: Int,
        val sendBuffer: Int,
        val receiveBuffer: Int,
        val pingUrl: String,
        val pingInterval: Int,
        val pingTimeout: Int
    )

    // ---------- Save / Load ----------
    fun saveConfig(config: TunnelConfig) {
        val json = gson.toJson(config)
        prefs.edit().putString("current_config", json).apply()
        LogManager.addLog("[ConfigManager] Config saved")
    }

    fun loadConfig(): TunnelConfig? {
        val json = prefs.getString("current_config", null)
        return if (json != null) {
            try {
                gson.fromJson(json, TunnelConfig::class.java)
            } catch (e: Exception) {
                LogManager.addLog("[ERROR] Failed to load config: ${e.message}")
                null
            }
        } else null
    }

    // ---------- Validation ----------
    fun validateConfig(config: TunnelConfig): Boolean {
        // SSH details must be non-empty and in correct format
        if (config.sshDetails.isEmpty()) {
            LogManager.addLog("[ERROR] SSH details empty")
            return false
        }
        if (!config.sshDetails.contains("@") || !config.sshDetails.contains(":")) {
            LogManager.addLog("[ERROR] Invalid SSH format. Use host:port@user:pass")
            return false
        }

        // Proxy can be empty, but if not, it must be valid
        if (config.proxyInput.isNotEmpty()) {
            // Basic check: must contain ':' or be Base64 (we'll trust parser)
            if (!config.proxyInput.contains(":") && !isBase64(config.proxyInput)) {
                LogManager.addLog("[WARN] Proxy does not contain ':' – assuming Base64?")
            }
        }

        // Payload must contain at least [host] or [proxy] if not empty
        if (config.payload.isNotEmpty()) {
            if (!config.payload.contains("[host]") && !config.payload.contains("[proxy]")) {
                LogManager.addLog("[WARN] Payload missing [host] or [proxy] placeholders")
            }
        }

        // Numeric fields validation
        if (config.mtu !in 1280..9000) {
            LogManager.addLog("[ERROR] MTU must be between 1280 and 9000")
            return false
        }
        if (config.splitDelay < 0) {
            LogManager.addLog("[ERROR] Split delay must be >= 0")
            return false
        }

        return true
    }

    private fun isBase64(str: String): Boolean {
        return try {
            android.util.Base64.decode(str, android.util.Base64.DEFAULT)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- Multiple Configs (Optional) ----------
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
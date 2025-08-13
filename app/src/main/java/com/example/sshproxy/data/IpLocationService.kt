package com.example.sshproxy.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class IpLocation(
    val ip: String,
    val country: String,
    val countryCode: String,
    val flag: String
)

object IpLocationService {
    private const val TAG = "IpLocationService"
    private const val CACHE_DURATION_NO_VPN_MS = 10 * 60 * 1000L // 10 minutes cache when no VPN
    private const val CACHE_DURATION_VPN_MS = Long.MAX_VALUE // Cache VPN IP for entire session
    
    private var cachedIpLocation: IpLocation? = null
    private var cachedSimpleIp: String? = null
    private var lastFetchTime = 0L
    private var lastKnownVpnState = false
    private var isVpnSession = false
    
    // Карта кодов стран на флаги эмодзи
    private val countryToFlag = mapOf(
        "US" to "🇺🇸", "GB" to "🇬🇧", "DE" to "🇩🇪", "FR" to "🇫🇷", "RU" to "🇷🇺",
        "CN" to "🇨🇳", "JP" to "🇯🇵", "IN" to "🇮🇳", "BR" to "🇧🇷", "CA" to "🇨🇦",
        "AU" to "🇦🇺", "IT" to "🇮🇹", "ES" to "🇪🇸", "MX" to "🇲🇽", "KR" to "🇰🇷",
        "NL" to "🇳🇱", "SE" to "🇸🇪", "NO" to "🇳🇴", "CH" to "🇨🇭", "FI" to "🇫🇮",
        "DK" to "🇩🇰", "BE" to "🇧🇪", "AT" to "🇦🇹", "IE" to "🇮🇪", "PL" to "🇵🇱",
        "CZ" to "🇨🇿", "HU" to "🇭🇺", "GR" to "🇬🇷", "PT" to "🇵🇹", "TR" to "🇹🇷",
        "IL" to "🇮🇱", "SA" to "🇸🇦", "AE" to "🇦🇪", "EG" to "🇪🇬", "ZA" to "🇿🇦",
        "NG" to "🇳🇬", "KE" to "🇰🇪", "GH" to "🇬🇭", "AR" to "🇦🇷", "CL" to "🇨🇱",
        "PE" to "🇵🇪", "CO" to "🇨🇴", "VE" to "🇻🇪", "TH" to "🇹🇭", "VN" to "🇻🇳",
        "MY" to "🇲🇾", "SG" to "🇸🇬", "ID" to "🇮🇩", "PH" to "🇵🇭", "BD" to "🇧🇩",
        "PK" to "🇵🇰", "LK" to "🇱🇰", "NZ" to "🇳🇿", "UA" to "🇺🇦", "BY" to "🇧🇾",
        "RO" to "🇷🇴", "BG" to "🇧🇬", "RS" to "🇷🇸", "HR" to "🇭🇷", "SI" to "🇸🇮",
        "SK" to "🇸🇰", "LT" to "🇱🇹", "LV" to "🇱🇻", "EE" to "🇪🇪", "IS" to "🇮🇸"
    )
    
    suspend fun getIpLocation(): IpLocation? = withContext(Dispatchers.IO) {
        // Check cache first with dynamic duration based on VPN state
        val now = System.currentTimeMillis()
        val cacheDuration = if (isVpnSession) CACHE_DURATION_VPN_MS else CACHE_DURATION_NO_VPN_MS
        
        if (cachedIpLocation != null && (now - lastFetchTime) < cacheDuration) {
            val cacheAge = (now - lastFetchTime) / 1000 // seconds
            val sessionType = if (isVpnSession) "VPN session" else "no VPN"
            Log.d(TAG, "Returning cached IP location (${cachedIpLocation?.ip}) - $sessionType, age: ${cacheAge}s")
            return@withContext cachedIpLocation
        }
        
        // Always get a reliable IPv4 address first
        val ipAddress = getReliableIp() ?: return@withContext null
        
        // Try to get country information 
        var country = "Unknown location"
        var countryCode = ""
        var flag = "🌍"
        
        try {
            // Пытаемся получить информацию о стране через ipapi.co
            val url = URL("https://ipapi.co/json/")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "SSH-Proxy-Android/1.0")
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                
                val json = JSONObject(response)
                val countryName = json.optString("country_name", "")
                val code = json.optString("country_code", "")
                
                if (countryName.isNotEmpty() && code.isNotEmpty()) {
                    country = countryName
                    countryCode = code
                    flag = countryToFlag[countryCode] ?: "🌍"
                    Log.d(TAG, "Country info: $country ($countryCode) $flag")
                } else {
                    Log.w(TAG, "No country data from ipapi.co")
                }
            } else {
                Log.w(TAG, "HTTP error from ipapi.co: $responseCode (using fallback country)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching country info, using fallback: ${e.message}")
        }
        
        // Create result with reliable IP and best available country info
        val ipLocation = IpLocation(ipAddress, country, countryCode, flag)
        
        // Cache the result
        cachedIpLocation = ipLocation
        lastFetchTime = now
        
        Log.d(TAG, "Final IP location: $ipAddress, $country ($countryCode) $flag")
        return@withContext ipLocation
    }
    
    private suspend fun getReliableIp(): String? = withContext(Dispatchers.IO) {
        // Try multiple services to get a clean IPv4 address
        val ipServices = listOf(
            "https://httpbin.org/ip" to "origin",
            "https://api.ipify.org?format=json" to "ip", 
            "https://ipinfo.io/ip" to null // returns plain text
        )
        
        for ((serviceUrl, jsonKey) in ipServices) {
            try {
                val url = URL(serviceUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", "SSH-Proxy-Android/1.0")
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.readText().trim()
                    reader.close()
                    
                    val ip = if (jsonKey != null) {
                        try {
                            JSONObject(response).optString(jsonKey, "")
                        } catch (e: Exception) {
                            ""
                        }
                    } else {
                        response // plain text response
                    }
                    
                    if (ip.isNotEmpty() && isValidIPv4(ip)) {
                        Log.d(TAG, "Got reliable IPv4 from $serviceUrl: $ip")
                        return@withContext ip
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get IP from $serviceUrl: ${e.message}")
            }
        }
        
        Log.e(TAG, "Failed to get reliable IP from all services")
        return@withContext null
    }
    
    private fun isValidIPv4(ip: String): Boolean {
        return try {
            val parts = ip.split(".")
            parts.size == 4 && parts.all { part ->
                val num = part.toIntOrNull()
                num != null && num in 0..255
            }
        } catch (e: Exception) {
            false
        }
    }
    
    suspend fun getSimpleIp(): String? = withContext(Dispatchers.IO) {
        // This now delegates to the reliable IP method
        return@withContext getReliableIp()
    }
    
    /**
     * Invalidate cache when VPN state changes (connect/disconnect/reconnect)
     * This forces fresh IP detection on the next request
     */
    fun invalidateCacheOnVpnChange(isVpnConnected: Boolean) {
        if (lastKnownVpnState != isVpnConnected) {
            Log.d(TAG, "VPN state changed: $lastKnownVpnState -> $isVpnConnected, invalidating IP cache")
            cachedIpLocation = null
            cachedSimpleIp = null
            lastFetchTime = 0L
            lastKnownVpnState = isVpnConnected
            isVpnSession = isVpnConnected
            
            val cacheStrategy = if (isVpnConnected) "session-long caching" else "10-minute caching"
            Log.d(TAG, "Switching to $cacheStrategy")
        }
    }
    
    /**
     * Force refresh IP info regardless of cache (for manual refresh button)
     */
    fun forceRefresh() {
        Log.d(TAG, "Force refresh requested, invalidating cache")
        cachedIpLocation = null
        cachedSimpleIp = null
        lastFetchTime = 0L
    }
    
    /**
     * Get cached IP info without triggering network requests
     * Returns null if no valid cache exists
     */
    fun getCachedIpLocation(): IpLocation? {
        val now = System.currentTimeMillis()
        val cacheDuration = if (isVpnSession) CACHE_DURATION_VPN_MS else CACHE_DURATION_NO_VPN_MS
        
        return if (cachedIpLocation != null && (now - lastFetchTime) < cacheDuration) {
            cachedIpLocation
        } else {
            null
        }
    }
}
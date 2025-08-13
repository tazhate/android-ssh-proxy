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
        try {
            // Используем ipapi.co для получения информации об IP и стране
            val url = URL("https://ipapi.co/json/")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "SSH-Proxy-Android/1.0")
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                
                val json = JSONObject(response)
                val ip = json.optString("ip", "")
                val country = json.optString("country_name", "")
                val countryCode = json.optString("country_code", "")
                
                if (ip.isNotEmpty() && country.isNotEmpty() && countryCode.isNotEmpty()) {
                    val flag = countryToFlag[countryCode] ?: "🌍"
                    Log.d(TAG, "IP location: $ip, $country ($countryCode) $flag")
                    return@withContext IpLocation(ip, country, countryCode, flag)
                } else {
                    Log.w(TAG, "Incomplete data from ipapi.co: $response")
                }
            } else {
                Log.w(TAG, "HTTP error: $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching IP location", e)
        }
        return@withContext null
    }
    
    suspend fun getSimpleIp(): String? = withContext(Dispatchers.IO) {
        try {
            // Fallback: простое получение IP через httpbin
            val url = URL("https://httpbin.org/ip")
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
                val ip = json.optString("origin", "")
                if (ip.isNotEmpty()) {
                    Log.d(TAG, "Simple IP: $ip")
                    return@withContext ip
                }
            } else {
                Log.w(TAG, "HTTP error from httpbin: $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching simple IP", e)
        }
        return@withContext null
    }
}
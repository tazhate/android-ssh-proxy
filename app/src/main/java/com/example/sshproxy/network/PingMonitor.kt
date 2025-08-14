package com.example.sshproxy.network

import android.content.Context
import android.util.Log
import com.example.sshproxy.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

data class PingResult(
    val latencyMs: Long,
    val isSuccessful: Boolean,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ServerStats(
    val averageLatencyMs: Long = 0,
    val minLatencyMs: Long = Long.MAX_VALUE,
    val maxLatencyMs: Long = 0,
    val packetLossPercentage: Float = 0f,
    val lastSuccessfulPing: Long = 0,
    val consecutiveFailures: Int = 0
)

class PingMonitor(
    private val hostname: String,
    private val port: Int,
    private val intervalMs: Long = 5000, // 5 seconds - more frequent pings
    private val timeoutMs: Int = 3000, // 3 seconds - faster timeout
    private val onConnectionIssue: ((consecutiveFailures: Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "PingMonitor"
        private const val PING_HISTORY_SIZE = 10
    }

    private var monitoringJob: Job? = null
    private var isMonitoring = false
    private var httpLatencyTester: HttpLatencyTester? = null
    
    private val _latestPing = MutableStateFlow<PingResult?>(null)
    val latestPing: StateFlow<PingResult?> = _latestPing.asStateFlow()
    
    private val _serverStats = MutableStateFlow(ServerStats())
    val serverStats: StateFlow<ServerStats> = _serverStats.asStateFlow()
    
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()
    
    private val pingHistory = mutableListOf<PingResult>()

    fun startMonitoring() {
        if (isMonitoring) {
            Log.d(TAG, "Already monitoring $hostname:$port")
            return
        }
        
        Log.d(TAG, "Starting HTTP latency monitoring via proxy")
        isMonitoring = true
        _isActive.value = true
        
        // Initialize HTTP latency tester
        httpLatencyTester = HttpLatencyTester(
            proxyHost = "127.0.0.1",
            timeoutMs = timeoutMs
        )
        
        monitoringJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (isMonitoring && currentCoroutineContext().isActive) {
                try {
                    // Perform both TCP ping (for quick connection check) and HTTP latency test
                    val tcpPingResult = performTcpPing()
                    
                    // If TCP ping fails, don't bother with HTTP test
                    val finalResult = if (!tcpPingResult.isSuccessful) {
                        tcpPingResult
                    } else {
                        // Perform HTTP latency test for real-world performance
                        val httpResult = httpLatencyTester?.performSingleTest()
                        if (httpResult != null && httpResult.successfulTests > 0) {
                            // Use HTTP latency as the primary metric
                            PingResult(
                                latencyMs = httpResult.averageLatencyMs,
                                isSuccessful = httpResult.successRate >= 50f, // At least 50% success rate
                                errorMessage = if (httpResult.successRate < 50f) "Poor HTTP connectivity: ${httpResult.successfulTests}/${httpResult.totalTests} successful" else null
                            )
                        } else {
                            // Fallback to TCP ping if HTTP test fails completely
                            tcpPingResult
                        }
                    }
                    
                    _latestPing.value = finalResult
                    updateStats(finalResult)
                    
                    delay(intervalMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in latency monitoring", e)
                    delay(intervalMs) // Still delay to prevent tight loop
                }
            }
        }
    }
    
    fun stopMonitoring() {
        Log.d(TAG, "Stopping HTTP latency monitoring")
        isMonitoring = false
        _isActive.value = false
        monitoringJob?.cancel()
        monitoringJob = null
        httpLatencyTester?.stopTesting()
        httpLatencyTester = null
    }
    
    suspend fun performSinglePing(): PingResult {
        return performTcpPing()
    }
    
    private suspend fun performTcpPing(): PingResult = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        
        try {
            socket = Socket()
            
            val latency = measureTimeMillis {
                socket.connect(InetSocketAddress(hostname, port), timeoutMs)
            }
            
            Log.d(TAG, "Ping to $hostname:$port: ${latency}ms")
            PingResult(latency, true)
            
        } catch (e: IOException) {
            val errorMsg = when {
                e.message?.contains("timeout") == true -> "Connection timeout"
                e.message?.contains("refused") == true -> "Connection refused"
                e.message?.contains("unreachable") == true -> "Host unreachable"
                else -> e.message ?: "Connection failed"
            }
            
            Log.d(TAG, "Ping failed to $hostname:$port: $errorMsg")
            PingResult(0, false, errorMsg)
            
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during ping", e)
            PingResult(0, false, e.message ?: "Unknown error")
            
        } finally {
            socket?.close()
        }
    }
    
    private fun updateStats(pingResult: PingResult) {
        synchronized(pingHistory) {
            // Add to history
            pingHistory.add(pingResult)
            if (pingHistory.size > PING_HISTORY_SIZE) {
                pingHistory.removeAt(0)
            }
            
            // Calculate stats
            val successfulPings = pingHistory.filter { it.isSuccessful }
            val latencies = successfulPings.map { it.latencyMs }
            
            val currentStats = _serverStats.value
            val newStats = if (latencies.isNotEmpty()) {
                val avgLatency = latencies.average().toLong()
                val minLatency = latencies.minOrNull() ?: 0
                val maxLatency = latencies.maxOrNull() ?: 0
                val packetLoss = ((pingHistory.size - successfulPings.size).toFloat() / pingHistory.size) * 100f
                val lastSuccess = if (pingResult.isSuccessful) pingResult.timestamp else currentStats.lastSuccessfulPing
                val consecutiveFailures = if (pingResult.isSuccessful) 0 else currentStats.consecutiveFailures + 1
                
                ServerStats(
                    averageLatencyMs = avgLatency,
                    minLatencyMs = minLatency,
                    maxLatencyMs = maxLatency,
                    packetLossPercentage = packetLoss,
                    lastSuccessfulPing = lastSuccess,
                    consecutiveFailures = consecutiveFailures
                )
            } else {
                currentStats.copy(
                    consecutiveFailures = currentStats.consecutiveFailures + 1
                )
            }
            
            _serverStats.value = newStats
            
            // Quick detection of connection issues - notify after 2 consecutive failures
            if (newStats.consecutiveFailures >= 2) {
                onConnectionIssue?.invoke(newStats.consecutiveFailures)
            }
        }
    }
    
    fun getConnectionQuality(): ConnectionQuality {
        val stats = _serverStats.value
        val latestPing = _latestPing.value
        
        return when {
            latestPing == null -> ConnectionQuality.UNKNOWN
            !latestPing.isSuccessful || stats.consecutiveFailures > 2 -> ConnectionQuality.POOR
            stats.averageLatencyMs > 1500 -> ConnectionQuality.POOR    // >1500ms = Poor
            stats.averageLatencyMs > 1000 -> ConnectionQuality.FAIR    // 1000-1500ms = Fair  
            stats.averageLatencyMs > 600 -> ConnectionQuality.GOOD     // 600-1000ms = Good
            else -> ConnectionQuality.EXCELLENT                        // <600ms = Excellent
        }
    }
    
    fun reset() {
        synchronized(pingHistory) {
            pingHistory.clear()
            _serverStats.value = ServerStats()
            _latestPing.value = null
        }
    }
}

enum class ConnectionQuality(val color: Int) {
    EXCELLENT(0xFF4CAF50.toInt()), // Green
    GOOD(0xFF8BC34A.toInt()),      // Light Green
    FAIR(0xFFFF9800.toInt()),      // Orange
    POOR(0xFFF44336.toInt()),      // Red
    UNKNOWN(0xFF9E9E9E.toInt());   // Gray
    
    fun getDisplayName(context: Context): String {
        return when (this) {
            EXCELLENT -> context.getString(R.string.quality_excellent)
            GOOD -> context.getString(R.string.quality_good)
            FAIR -> context.getString(R.string.quality_fair)
            POOR -> context.getString(R.string.quality_poor)
            UNKNOWN -> context.getString(R.string.quality_unknown)
        }
    }
}
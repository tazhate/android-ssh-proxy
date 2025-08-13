package com.example.sshproxy.service

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.pow

/**
 * Monitors SSH connection health and manages automatic reconnection with exponential backoff
 */
class ConnectionHealthMonitor(
    private val healthCheckIntervalMs: Long = 30_000L, // 30 seconds
    private val maxReconnectAttempts: Int = 10,
    private val initialBackoffMs: Long = 1_000L, // 1 second
    private val maxBackoffMs: Long = 300_000L, // 5 minutes
    private val backoffMultiplier: Double = 2.0
) {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val reconnectAttempts = AtomicInteger(0)
    private var healthCheckJob: Job? = null
    private var reconnectJob: Job? = null
    
    enum class ConnectionState {
        CONNECTED,
        DISCONNECTED,
        RECONNECTING,
        FAILED
    }
    
    data class ReconnectStrategy(
        val attempt: Int,
        val delayMs: Long,
        val shouldRetry: Boolean
    )
    
    /**
     * Start monitoring connection health
     */
    fun startMonitoring(
        scope: CoroutineScope,
        isConnectionAlive: suspend () -> Boolean,
        onReconnect: suspend () -> Boolean
    ) {
        stopMonitoring()
        
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(healthCheckIntervalMs)
                
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    try {
                        val isAlive = withTimeout(5000L) { isConnectionAlive() }
                        
                        if (!isAlive) {
                            handleConnectionLost(scope, onReconnect)
                        } else {
                            // Connection is healthy, reset attempts counter
                            reconnectAttempts.set(0)
                        }
                    } catch (e: Exception) {
                        handleConnectionLost(scope, onReconnect)
                    }
                }
            }
        }
    }
    
    /**
     * Handle connection loss and initiate reconnection
     */
    private fun handleConnectionLost(
        scope: CoroutineScope,
        onReconnect: suspend () -> Boolean
    ) {
        if (_connectionState.value == ConnectionState.RECONNECTING) {
            return // Already reconnecting
        }
        
        _connectionState.value = ConnectionState.RECONNECTING
        
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (isActive) {
                val strategy = getReconnectStrategy()
                
                if (!strategy.shouldRetry) {
                    _connectionState.value = ConnectionState.FAILED
                    break
                }
                
                delay(strategy.delayMs)
                
                try {
                    val success = withTimeout(10_000L) { onReconnect() }
                    
                    if (success) {
                        _connectionState.value = ConnectionState.CONNECTED
                        reconnectAttempts.set(0)
                        break
                    }
                } catch (e: Exception) {
                    // Reconnection failed, will retry
                }
                
                reconnectAttempts.incrementAndGet()
            }
        }
    }
    
    /**
     * Calculate reconnection strategy with exponential backoff
     */
    private fun getReconnectStrategy(): ReconnectStrategy {
        val attempt = reconnectAttempts.get()
        val shouldRetry = attempt < maxReconnectAttempts
        
        val delayMs = if (shouldRetry) {
            val exponentialDelay = initialBackoffMs * backoffMultiplier.pow(attempt.toDouble())
            min(exponentialDelay.toLong(), maxBackoffMs)
        } else {
            0L
        }
        
        return ReconnectStrategy(
            attempt = attempt,
            delayMs = delayMs,
            shouldRetry = shouldRetry
        )
    }
    
    /**
     * Notify that connection has been established
     */
    fun onConnectionEstablished() {
        _connectionState.value = ConnectionState.CONNECTED
        reconnectAttempts.set(0)
    }
    
    /**
     * Notify that connection has been lost
     */
    fun onConnectionLost() {
        _connectionState.value = ConnectionState.DISCONNECTED
    }
    
    /**
     * Stop monitoring and cancel all jobs
     */
    fun stopMonitoring() {
        healthCheckJob?.cancel()
        healthCheckJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        _connectionState.value = ConnectionState.DISCONNECTED
        reconnectAttempts.set(0)
    }
    
    /**
     * Reset reconnection attempts counter
     */
    fun resetReconnectAttempts() {
        reconnectAttempts.set(0)
    }
    
    /**
     * Get current number of reconnection attempts
     */
    fun getReconnectAttempts(): Int = reconnectAttempts.get()
}

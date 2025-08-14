package com.example.sshproxy.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.util.Base64
import kotlin.system.measureTimeMillis

data class HttpLatencyResult(
    val url: String,
    val latencyMs: Long,
    val isSuccessful: Boolean,
    val httpStatusCode: Int = 0,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class AggregatedLatencyResult(
    val averageLatencyMs: Long,
    val medianLatencyMs: Long,
    val minLatencyMs: Long,
    val maxLatencyMs: Long,
    val successfulTests: Int,
    val totalTests: Int,
    val successRate: Float,
    val individualResults: List<HttpLatencyResult>,
    val timestamp: Long = System.currentTimeMillis()
)

class HttpLatencyTester(
    private val proxyHost: String? = "127.0.0.1",
    private val proxyPort: Int? = 8080,
    private val timeoutMs: Int = 10000 // 10 seconds
) {
    companion object {
        private const val TAG = "HttpLatencyTester"
        
        // Test endpoints for latency measurement
        private val TEST_ENDPOINTS = listOf(
            "https://tazhate.com",
            "https://httpbin.org/status/200",
            "https://www.google.com/generate_204", // Google connectivity check
            "https://detectportal.firefox.com/success.txt", // Firefox connectivity check
            "https://connectivitycheck.gstatic.com/generate_204", // Google static connectivity check
            "https://clients3.google.com/generate_204", // Alternative Google check
            "https://www.msftconnecttest.com/connecttest.txt" // Microsoft connectivity check
        )
    }

    private var testingJob: Job? = null
    private var isRunning = false
    
    private val _latestResult = MutableStateFlow<AggregatedLatencyResult?>(null)
    val latestResult: StateFlow<AggregatedLatencyResult?> = _latestResult.asStateFlow()
    
    // SSL endpoints that might have certificate issues
    private val problematicSslEndpoints = setOf(
        "https://www.msftconnecttest.com/connecttest.txt"
    )
    
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    fun startContinuousTesting(intervalMs: Long = 10000) {
        if (isRunning) {
            Log.d(TAG, "HTTP latency testing already running")
            return
        }
        
        Log.d(TAG, "Starting continuous HTTP latency testing")
        isRunning = true
        _isActive.value = true
        
        testingJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (isRunning && currentCoroutineContext().isActive) {
                try {
                    val result = performLatencyTest()
                    _latestResult.value = result
                    
                    delay(intervalMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in continuous latency testing", e)
                    delay(intervalMs) // Still delay to prevent tight loop
                }
            }
        }
    }
    
    fun stopTesting() {
        Log.d(TAG, "Stopping HTTP latency testing")
        isRunning = false
        _isActive.value = false
        testingJob?.cancel()
        testingJob = null
    }
    
    suspend fun performSingleTest(): AggregatedLatencyResult {
        return performLatencyTest()
    }
    
    private suspend fun performLatencyTest(): AggregatedLatencyResult = withContext(Dispatchers.IO) {
        val results = mutableListOf<HttpLatencyResult>()
        
        // Test all endpoints in parallel for faster results
        val jobs = TEST_ENDPOINTS.map { endpoint ->
            async {
                testSingleEndpoint(endpoint)
            }
        }
        
        // Wait for all tests to complete
        jobs.awaitAll().forEach { result ->
            results.add(result)
        }
        
        // Calculate aggregated statistics
        val successfulResults = results.filter { it.isSuccessful }
        val latencies = successfulResults.map { it.latencyMs }
        
        val aggregatedResult = if (latencies.isNotEmpty()) {
            val sortedLatencies = latencies.sorted()
            AggregatedLatencyResult(
                averageLatencyMs = latencies.average().toLong(),
                medianLatencyMs = if (sortedLatencies.size % 2 == 0) {
                    (sortedLatencies[sortedLatencies.size / 2 - 1] + sortedLatencies[sortedLatencies.size / 2]) / 2
                } else {
                    sortedLatencies[sortedLatencies.size / 2]
                },
                minLatencyMs = latencies.minOrNull() ?: 0,
                maxLatencyMs = latencies.maxOrNull() ?: 0,
                successfulTests = successfulResults.size,
                totalTests = results.size,
                successRate = (successfulResults.size.toFloat() / results.size) * 100f,
                individualResults = results
            )
        } else {
            // All tests failed
            AggregatedLatencyResult(
                averageLatencyMs = 0,
                medianLatencyMs = 0,
                minLatencyMs = 0,
                maxLatencyMs = 0,
                successfulTests = 0,
                totalTests = results.size,
                successRate = 0f,
                individualResults = results
            )
        }
        
        Log.d(TAG, "HTTP latency test completed: ${aggregatedResult.successfulTests}/${aggregatedResult.totalTests} successful, avg: ${aggregatedResult.averageLatencyMs}ms")
        
        aggregatedResult
    }
    
    private suspend fun testSingleEndpoint(endpoint: String): HttpLatencyResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(endpoint)
            val connection: HttpURLConnection
            if (proxyHost != null && proxyPort != null) {
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
                connection = url.openConnection(proxy) as HttpURLConnection
            } else {
                connection = url.openConnection() as HttpURLConnection
            }
            
            val latency = measureTimeMillis {
                
                // Handle SSL bypass for problematic endpoints
                if (connection is HttpsURLConnection && endpoint in problematicSslEndpoints) {
                    val httpsConnection = connection as HttpsURLConnection
                    httpsConnection.sslSocketFactory = createTrustAllSSLContext().socketFactory
                    httpsConnection.hostnameVerifier = HostnameVerifier { _: String, _: SSLSession -> true }
                }
                
                connection!!.apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("User-Agent", "SSH-Proxy-Latency-Test/1.0")
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Connection", "close")
                }
                
                val responseCode = connection!!.responseCode
                
                // For some endpoints, we need to read the response to complete the request
                if (responseCode == 200) {
                    connection!!.inputStream.use { it.read() }
                }
            }
            
            val responseCode = connection!!.responseCode
            
            Log.d(TAG, "HTTP test to $endpoint: ${latency}ms, status: $responseCode")
            
            HttpLatencyResult(
                url = endpoint,
                latencyMs = latency,
                isSuccessful = responseCode in 200..299,
                httpStatusCode = responseCode
            )
            
        } catch (e: Exception) {
            val errorMsg = when {
                e.message?.contains("timeout") == true -> "Connection timeout"
                e.message?.contains("refused") == true -> "Connection refused"
                e.message?.contains("unreachable") == true -> "Host unreachable"
                e.message?.contains("proxy") == true -> "Proxy error"
                else -> e.message ?: "HTTP request failed"
            }
            
            Log.d(TAG, "HTTP test failed for $endpoint: $errorMsg")
            
            HttpLatencyResult(
                url = endpoint,
                latencyMs = 0,
                isSuccessful = false,
                errorMessage = errorMsg
            )
            
        } finally {
            // connection?.disconnect()
        }
    }
    
    private fun createTrustAllSSLContext(): SSLContext {
        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )
        
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext
    }
    
    fun reset() {
        _latestResult.value = null
    }
}
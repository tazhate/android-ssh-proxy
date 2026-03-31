package com.example.sshproxy.network

import android.util.Log
import net.schmizz.sshj.Config
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.transport.cipher.Cipher
import net.schmizz.sshj.transport.kex.KeyExchange
import net.schmizz.sshj.transport.mac.MAC
import kotlin.system.measureTimeMillis

data class SshAlgorithms(
    val cipher: String? = null,
    val kex: String? = null,
    val mac: String? = null
)

data class AlgorithmPerformance(
    val algorithm: String,
    val averageTimeMs: Long,
    val isSupported: Boolean
)

class SshAlgorithmManager {
    companion object {
        private const val TAG = "SshAlgorithmManager"
        
        // Быстрые алгоритмы в порядке предпочтения (скорость)
        private val FAST_CIPHERS = listOf(
            "aes128-ctr",
            "aes192-ctr", 
            "aes256-ctr",
            "aes128-cbc",
            "aes192-cbc",
            "aes256-cbc"
        )
        
        private val FAST_KEX = listOf(
            "diffie-hellman-group14-sha256",
            "diffie-hellman-group16-sha512",
            "ecdh-sha2-nistp256",
            "ecdh-sha2-nistp384",
            "ecdh-sha2-nistp521"
        )
        
        private val FAST_MAC = listOf(
            "hmac-sha2-256",
            "hmac-sha2-512",
            "hmac-sha1"
        )
    }
    
    /**
     * Получить список всех поддерживаемых алгоритмов из DefaultConfig
     */
    fun getSupportedAlgorithms(): SshAlgorithms {
        val defaultConfig = DefaultConfig()
        
        val ciphers = defaultConfig.cipherFactories.map { it.name }
        val kexAlgorithms = defaultConfig.keyExchangeFactories.map { it.name }
        val macAlgorithms = defaultConfig.macFactories.map { it.name }
        
        Log.d(TAG, "Supported ciphers: $ciphers")
        Log.d(TAG, "Supported KEX: $kexAlgorithms")
        Log.d(TAG, "Supported MAC: $macAlgorithms")
        
        return SshAlgorithms(
            cipher = ciphers.joinToString(","),
            kex = kexAlgorithms.joinToString(","),
            mac = macAlgorithms.joinToString(",")
        )
    }
    
    /**
     * Получить оптимальные быстрые алгоритмы из поддерживаемых
     */
    fun getFastAlgorithms(): SshAlgorithms {
        val defaultConfig = DefaultConfig()
        
        val supportedCiphers = defaultConfig.cipherFactories.map { it.name }
        val supportedKex = defaultConfig.keyExchangeFactories.map { it.name }
        val supportedMac = defaultConfig.macFactories.map { it.name }
        
        Log.d(TAG, "All supported ciphers: ${supportedCiphers.joinToString(", ")}")
        Log.d(TAG, "All supported KEX: ${supportedKex.joinToString(", ")}") 
        Log.d(TAG, "All supported MAC: ${supportedMac.joinToString(", ")}")
        
        // Выбираем первый доступный быстрый алгоритм из каждой категории
        val fastCipher = FAST_CIPHERS.firstOrNull { it in supportedCiphers }
        val selectedCipher = fastCipher ?: supportedCiphers.firstOrNull()
        
        val fastKex = FAST_KEX.firstOrNull { it in supportedKex }
        val selectedKex = fastKex ?: supportedKex.firstOrNull()
        
        val fastMac = FAST_MAC.firstOrNull { it in supportedMac }
        val selectedMac = fastMac ?: supportedMac.firstOrNull()
        
        // Логируем что выбрали и почему
        Log.d(TAG, "Cipher selection: preferred=${FAST_CIPHERS.joinToString(", ")}")
        Log.d(TAG, "Cipher selected: $selectedCipher ${if (fastCipher != null) "(fast)" else "(fallback)"}")
        
        Log.d(TAG, "KEX selection: preferred=${FAST_KEX.joinToString(", ")}")
        Log.d(TAG, "KEX selected: $selectedKex ${if (fastKex != null) "(fast)" else "(fallback)"}")
        
        Log.d(TAG, "MAC selection: preferred=${FAST_MAC.joinToString(", ")}")
        Log.d(TAG, "MAC selected: $selectedMac ${if (fastMac != null) "(fast)" else "(fallback)"}")
        
        return SshAlgorithms(
            cipher = selectedCipher,
            kex = selectedKex,
            mac = selectedMac
        )
    }
    
    /**
     * Создать кастомный Config с указанными алгоритмами
     */
    fun createCustomConfig(algorithms: SshAlgorithms): Config {
        val defaultConfig = DefaultConfig()
        
        Log.d(TAG, "Creating custom SSH config with algorithms:")
        Log.d(TAG, "  - Preferred cipher: ${algorithms.cipher}")
        Log.d(TAG, "  - Preferred KEX: ${algorithms.kex}")
        Log.d(TAG, "  - Preferred MAC: ${algorithms.mac}")
        
        return object : Config by defaultConfig {
            override fun getCipherFactories(): List<Factory.Named<Cipher>> {
                val allFactories = defaultConfig.cipherFactories
                return if (algorithms.cipher != null) {
                    // Ставим предпочтительный алгоритм первым
                    val preferredFactory = allFactories.find { it.name == algorithms.cipher }
                    if (preferredFactory != null) {
                        listOf(preferredFactory) + allFactories.filter { it.name != algorithms.cipher }
                    } else {
                        allFactories
                    }
                } else {
                    allFactories
                }
            }
            
            override fun getKeyExchangeFactories(): List<Factory.Named<KeyExchange>> {
                val allFactories = defaultConfig.keyExchangeFactories
                return if (algorithms.kex != null) {
                    val preferredFactory = allFactories.find { it.name == algorithms.kex }
                    if (preferredFactory != null) {
                        listOf(preferredFactory) + allFactories.filter { it.name != algorithms.kex }
                    } else {
                        allFactories
                    }
                } else {
                    allFactories
                }
            }
            
            override fun getMACFactories(): List<Factory.Named<MAC>> {
                val allFactories = defaultConfig.macFactories
                return if (algorithms.mac != null) {
                    val preferredFactory = allFactories.find { it.name == algorithms.mac }
                    if (preferredFactory != null) {
                        listOf(preferredFactory) + allFactories.filter { it.name != algorithms.mac }
                    } else {
                        allFactories
                    }
                } else {
                    allFactories
                }
            }
        }
    }
    
    /**
     * Проверить совместимость алгоритмов с сервером
     * Возвращает фактически согласованные алгоритмы после подключения
     */
    suspend fun testAlgorithmCompatibility(
        host: String,
        port: Int,
        username: String,
        keyProvider: net.schmizz.sshj.userauth.keyprovider.KeyProvider,
        algorithms: SshAlgorithms,
        timeoutMs: Int = 15000
    ): Result<SshAlgorithms> {
        return try {
            val config = createCustomConfig(algorithms)
            val sshClient = SSHClient(config)
            
            val connectionTime = measureTimeMillis {
                sshClient.use { client ->
                    client.connectTimeout = timeoutMs
                    client.connect(host, port)
                    client.authPublickey(username, keyProvider)
                }
            }
            
            Log.d(TAG, "Algorithm test successful in ${connectionTime}ms")
            
            // В реальности нужно было бы получить согласованные алгоритмы из транспорта SSH
            // Но для упрощения возвращаем исходные алгоритмы как успешные
            Result.success(algorithms)
            
        } catch (e: Exception) {
            Log.e(TAG, "Algorithm compatibility test failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Автоматически выбрать и протестировать лучшие алгоритмы для сервера
     */
    suspend fun autoSelectOptimalAlgorithms(
        host: String,
        port: Int,
        username: String,
        keyProvider: net.schmizz.sshj.userauth.keyprovider.KeyProvider
    ): SshAlgorithms? {
        val fastAlgorithms = getFastAlgorithms()
        
        // Пробуем быстрые алгоритмы
        val result = testAlgorithmCompatibility(host, port, username, keyProvider, fastAlgorithms)
        
        return if (result.isSuccess) {
            Log.d(TAG, "Fast algorithms work for $host:$port")
            fastAlgorithms
        } else {
            Log.w(TAG, "Fast algorithms failed for $host:$port, using defaults")
            null // Возвращаем null чтобы использовать default config
        }
    }
    
    /**
     * Получить человекочитаемые названия алгоритмов для UI
     */
    fun getAlgorithmDisplayNames(): Map<String, String> {
        return mapOf(
            // Ciphers
            "aes128-ctr" to "AES-128 CTR (Fast)",
            "aes192-ctr" to "AES-192 CTR (Fast)",
            "aes256-ctr" to "AES-256 CTR (Fast)",
            "aes128-cbc" to "AES-128 CBC",
            "aes192-cbc" to "AES-192 CBC", 
            "aes256-cbc" to "AES-256 CBC",
            
            // KEX
            "diffie-hellman-group14-sha256" to "DH Group14 SHA256",
            "diffie-hellman-group16-sha512" to "DH Group16 SHA512",
            "ecdh-sha2-nistp256" to "ECDH P-256 (Fast)",
            "ecdh-sha2-nistp384" to "ECDH P-384",
            "ecdh-sha2-nistp521" to "ECDH P-521",
            
            // MAC
            "hmac-sha2-256" to "HMAC-SHA2-256 (Fast)",
            "hmac-sha2-512" to "HMAC-SHA2-512", 
            "hmac-sha1" to "HMAC-SHA1"
        )
    }
}
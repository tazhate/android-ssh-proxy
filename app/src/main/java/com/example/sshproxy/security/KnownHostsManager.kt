package com.example.sshproxy.security

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages known SSH host keys for fingerprint validation
 * Similar to ~/.ssh/known_hosts but with Android-specific enhancements
 */
class KnownHostsManager(private val context: Context) {
    
    companion object {
        private const val TAG = "KnownHostsManager"
        private const val KNOWN_HOSTS_FILE = "known_hosts"
    }

    private val knownHosts = ConcurrentHashMap<String, String>()
    private val knownHostsFile = File(context.filesDir, KNOWN_HOSTS_FILE)

    init {
        loadKnownHosts()
    }

    /**
     * Data class representing a host key entry
     */
    data class HostKeyInfo(
        val hostname: String,
        val port: Int,
        val keyType: String,
        val fingerprint: String,
        val publicKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as HostKeyInfo

            if (hostname != other.hostname) return false
            if (port != other.port) return false
            if (keyType != other.keyType) return false
            if (fingerprint != other.fingerprint) return false
            if (!publicKey.contentEquals(other.publicKey)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = hostname.hashCode()
            result = 31 * result + port
            result = 31 * result + keyType.hashCode()
            result = 31 * result + fingerprint.hashCode()
            result = 31 * result + publicKey.contentHashCode()
            return result
        }
    }

    /**
     * Check if we know this host and validate its key
     * @param hostname Server hostname/IP
     * @param port Server port
     * @param publicKey Server's public key
     * @param keyType Key type (ssh-rsa, ssh-ed25519, etc.)
     * @return HostKeyValidationResult
     */
    fun validateHostKey(hostname: String, port: Int, publicKey: ByteArray, keyType: String): HostKeyValidationResult {
        val hostKey = generateHostKey(hostname, port)
        val fingerprint = generateFingerprint(publicKey)
        
        Log.d(TAG, "Validating host key for $hostname:$port")
        Log.d(TAG, "Key type: $keyType, Fingerprint: $fingerprint")

        val storedFingerprint = knownHosts[hostKey]
        
        return when {
            storedFingerprint == null -> {
                Log.i(TAG, "New host $hostname:$port - storing fingerprint")
                HostKeyValidationResult.NEW_HOST
            }
            storedFingerprint == fingerprint -> {
                Log.d(TAG, "Host key matches for $hostname:$port")
                HostKeyValidationResult.VALID
            }
            else -> {
                Log.w(TAG, "Host key mismatch for $hostname:$port!")
                Log.w(TAG, "Stored: $storedFingerprint")
                Log.w(TAG, "Received: $fingerprint")
                HostKeyValidationResult.KEY_CHANGED
            }
        }
    }

    /**
     * Store a host key after validation
     * @param hostname Server hostname/IP
     * @param port Server port
     * @param publicKey Server's public key
     * @param keyType Key type
     */
    fun storeHostKey(hostname: String, port: Int, publicKey: ByteArray, keyType: String) {
        val hostKey = generateHostKey(hostname, port)
        val fingerprint = generateFingerprint(publicKey)
        
        knownHosts[hostKey] = fingerprint
        saveKnownHosts()
        
        Log.i(TAG, "Stored host key for $hostname:$port with fingerprint $fingerprint")
    }

    /**
     * Get stored fingerprint for a host
     * @param hostname Server hostname/IP
     * @param port Server port
     * @return Stored fingerprint or null if not known
     */
    fun getStoredFingerprint(hostname: String, port: Int): String? {
        val hostKey = generateHostKey(hostname, port)
        return knownHosts[hostKey]
    }

    /**
     * Remove a host key (for when user wants to reset/remove)
     * @param hostname Server hostname/IP
     * @param port Server port
     */
    fun removeHostKey(hostname: String, port: Int) {
        val hostKey = generateHostKey(hostname, port)
        knownHosts.remove(hostKey)
        saveKnownHosts()
        Log.i(TAG, "Removed host key for $hostname:$port")
    }

    /**
     * Generate SHA-256 fingerprint from public key
     */
    private fun generateFingerprint(publicKey: ByteArray): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(publicKey)
            "SHA256:" + android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate fingerprint", e)
            "ERROR:${e.message}"
        }
    }

    /**
     * Generate host key identifier
     */
    private fun generateHostKey(hostname: String, port: Int): String {
        return if (port == 22) hostname else "[$hostname]:$port"
    }

    /**
     * Load known hosts from file
     */
    private fun loadKnownHosts() {
        try {
            if (!knownHostsFile.exists()) {
                Log.d(TAG, "Known hosts file does not exist, starting fresh")
                return
            }

            knownHostsFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split(" ", limit = 2)
                    if (parts.size == 2) {
                        knownHosts[parts[0]] = parts[1]
                    }
                }
            }
            
            Log.d(TAG, "Loaded ${knownHosts.size} known hosts")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load known hosts", e)
        }
    }

    /**
     * Save known hosts to file
     */
    private fun saveKnownHosts() {
        try {
            val content = knownHosts.entries.joinToString("\n") { "${it.key} ${it.value}" }
            knownHostsFile.writeText(content)
            Log.d(TAG, "Saved ${knownHosts.size} known hosts to file")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save known hosts", e)
        }
    }

    /**
     * Result of host key validation
     */
    enum class HostKeyValidationResult {
        /** Host is new, key should be stored */
        NEW_HOST,
        /** Host key matches stored fingerprint */
        VALID,
        /** Host key has changed - potential security issue */
        KEY_CHANGED
    }
}
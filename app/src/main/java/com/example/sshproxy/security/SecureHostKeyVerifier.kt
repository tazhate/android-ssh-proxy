package com.example.sshproxy.security

import android.content.Context
import android.util.Log
import com.example.sshproxy.AppLog
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Custom host key verifier that implements known_hosts validation
 * Replaces the promiscuous verifier with proper security checks
 */
class SecureHostKeyVerifier(private val context: Context) : HostKeyVerifier {
    
    companion object {
        private const val TAG = "SecureHostKeyVerifier"
    }

    private val knownHostsManager = KnownHostsManager(context)
    
    // Callback for when host key validation fails
    var onHostKeyMismatch: (suspend (hostname: String, port: Int, fingerprint: String, storedFingerprint: String) -> Boolean)? = null

    override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> {
        // Return empty list - we don't restrict algorithms, just validate keys
        return mutableListOf()
    }

    override fun verify(hostname: String?, port: Int, key: java.security.PublicKey?): Boolean {
        if (hostname == null || key == null) {
            Log.e(TAG, "Invalid hostname or key provided")
            return false
        }

        return try {
            val keyBytes = key.encoded
            val keyType = determineKeyType(key)
            
            Log.d(TAG, "Verifying host key for $hostname:$port (type: $keyType)")
            
            when (val result = knownHostsManager.validateHostKey(hostname, port, keyBytes, keyType)) {
                KnownHostsManager.HostKeyValidationResult.NEW_HOST -> {
                    // First connection - store the key
                    knownHostsManager.storeHostKey(hostname, port, keyBytes, keyType)
                    AppLog.log("New SSH server $hostname:$port - fingerprint stored")
                    true
                }
                
                KnownHostsManager.HostKeyValidationResult.VALID -> {
                    // Key matches - connection is safe
                    Log.d(TAG, "Host key validation successful for $hostname:$port")
                    true
                }
                
                KnownHostsManager.HostKeyValidationResult.KEY_CHANGED -> {
                    // Key changed - potential security issue
                    val fingerprint = generateFingerprint(keyBytes)
                    val storedFingerprint = knownHostsManager.getStoredFingerprint(hostname, port) ?: "unknown"
                    
                    Log.w(TAG, "Host key changed for $hostname:$port!")
                    AppLog.log("WARNING: Host key changed for $hostname:$port")
                    AppLog.log("Stored fingerprint: $storedFingerprint")
                    AppLog.log("Received fingerprint: $fingerprint")
                    
                    // For now, return false - we need async user confirmation
                    // The service will handle this differently using verifyWithUserConfirmation
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Host key verification failed", e)
            AppLog.log("Host key verification error: ${e.message}")
            false
        }
    }

    /**
     * Verify host key with user confirmation for changed keys
     * This is used when we need to ask the user about key changes
     */
    suspend fun verifyWithUserConfirmation(hostname: String, port: Int, key: java.security.PublicKey): Boolean {
        val keyBytes = key.encoded
        val keyType = determineKeyType(key)
        
        when (val result = knownHostsManager.validateHostKey(hostname, port, keyBytes, keyType)) {
            KnownHostsManager.HostKeyValidationResult.NEW_HOST -> {
                knownHostsManager.storeHostKey(hostname, port, keyBytes, keyType)
                AppLog.log("New SSH server $hostname:$port - fingerprint stored")
                return true
            }
            
            KnownHostsManager.HostKeyValidationResult.VALID -> {
                return true
            }
            
            KnownHostsManager.HostKeyValidationResult.KEY_CHANGED -> {
                val fingerprint = generateFingerprint(keyBytes)
                val storedFingerprint = knownHostsManager.getStoredFingerprint(hostname, port) ?: "unknown"
                
                // Ask user for confirmation
                val userAccepted = onHostKeyMismatch?.invoke(hostname, port, fingerprint, storedFingerprint) ?: false
                
                if (userAccepted) {
                    // User accepted - update the stored key
                    knownHostsManager.storeHostKey(hostname, port, keyBytes, keyType)
                    AppLog.log("Host key updated for $hostname:$port with user confirmation")
                    return true
                } else {
                    AppLog.log("Connection rejected - host key change not accepted")
                    return false
                }
            }
        }
    }

    /**
     * Get stored fingerprint for a server (for display purposes)
     */
    fun getServerFingerprint(hostname: String, port: Int): String? {
        return knownHostsManager.getStoredFingerprint(hostname, port)
    }

    /**
     * Remove stored host key (when user wants to reset)
     */
    fun removeHostKey(hostname: String, port: Int) {
        knownHostsManager.removeHostKey(hostname, port)
    }

    /**
     * Determine SSH key type from PublicKey
     */
    private fun determineKeyType(key: java.security.PublicKey): String {
        return when (key.algorithm.lowercase()) {
            "rsa" -> "ssh-rsa"
            "ec" -> "ecdsa-sha2-nistp256" // Assuming P-256 curve
            "eddsa", "ed25519" -> "ssh-ed25519"
            else -> "ssh-${key.algorithm.lowercase()}"
        }
    }

    /**
     * Generate SHA-256 fingerprint from public key bytes
     */
    private fun generateFingerprint(publicKey: ByteArray): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(publicKey)
            "SHA256:" + android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        } catch (e: Exception) {
            "ERROR:${e.message}"
        }
    }
}
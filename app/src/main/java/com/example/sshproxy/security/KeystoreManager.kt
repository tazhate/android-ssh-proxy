package com.example.sshproxy.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages encryption/decryption using Android Keystore for secure SSH private key storage
 */
class KeystoreManager {
    
    companion object {
        private const val TAG = "KeystoreManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS_PREFIX = "ssh_key_"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    /**
     * Generate or get existing encryption key for SSH private key
     */
    private fun getOrCreateSecretKey(keyId: String): SecretKey {
        val keyAlias = "$KEY_ALIAS_PREFIX$keyId"
        
        return if (keyStore.containsAlias(keyAlias)) {
            keyStore.getKey(keyAlias, null) as SecretKey
        } else {
            generateSecretKey(keyAlias)
        }
    }

    /**
     * Generate new AES key in Android Keystore
     */
    private fun generateSecretKey(keyAlias: String): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Require user authentication for key access (optional - can be enabled for extra security)
            // .setUserAuthenticationRequired(true)
            // .setUserAuthenticationValidityDurationSeconds(300) // 5 minutes
            .build()

        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypt private key data
     * @param keyId SSH key identifier
     * @param plaintext Private key content as byte array
     * @return EncryptedData containing ciphertext and IV
     */
    fun encryptPrivateKey(keyId: String, plaintext: ByteArray): EncryptedData {
        try {
            val secretKey = getOrCreateSecretKey(keyId)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)
            
            Log.d(TAG, "Successfully encrypted private key for keyId: $keyId")
            return EncryptedData(ciphertext, iv)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt private key for keyId: $keyId", e)
            throw SecurityException("Failed to encrypt private key", e)
        }
    }

    /**
     * Decrypt private key data
     * @param keyId SSH key identifier
     * @param encryptedData EncryptedData containing ciphertext and IV
     * @return Decrypted private key content as byte array
     */
    fun decryptPrivateKey(keyId: String, encryptedData: EncryptedData): ByteArray {
        try {
            val secretKey = getOrCreateSecretKey(keyId)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            
            val plaintext = cipher.doFinal(encryptedData.ciphertext)
            Log.d(TAG, "Successfully decrypted private key for keyId: $keyId")
            return plaintext
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt private key for keyId: $keyId", e)
            throw SecurityException("Failed to decrypt private key", e)
        }
    }

    /**
     * Delete encryption key from Android Keystore
     * @param keyId SSH key identifier
     */
    fun deleteEncryptionKey(keyId: String) {
        try {
            val keyAlias = "$KEY_ALIAS_PREFIX$keyId"
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
                Log.d(TAG, "Deleted encryption key for keyId: $keyId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete encryption key for keyId: $keyId", e)
        }
    }

    /**
     * Check if encryption key exists for given SSH key ID
     */
    fun hasEncryptionKey(keyId: String): Boolean {
        return try {
            val keyAlias = "$KEY_ALIAS_PREFIX$keyId"
            keyStore.containsAlias(keyAlias)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check encryption key existence for keyId: $keyId", e)
            false
        }
    }

    /**
     * Data class to hold encrypted content and initialization vector
     */
    data class EncryptedData(
        val ciphertext: ByteArray,
        val iv: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EncryptedData

            if (!ciphertext.contentEquals(other.ciphertext)) return false
            if (!iv.contentEquals(other.iv)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = ciphertext.contentHashCode()
            result = 31 * result + iv.contentHashCode()
            return result
        }
    }
}
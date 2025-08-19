
package com.example.sshproxy.security

import android.content.Context

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

object KeyPasswordKeystore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS_PREFIX = "pem_key_password_"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PASSWORD_LENGTH = 100
    private const val CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+[]{};:,.<>/?|"
    private const val IV_LENGTH = 16

    fun getOrCreatePassword(context: Context, keyId: String): String {
        val keyAlias = KEY_ALIAS_PREFIX + keyId
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) {
            val secretKey = keyStore.getKey(keyAlias, null) as SecretKey
            val (iv, encrypted) = KeyPasswordStorage.getEncryptedPassword(context, keyId)
                ?: return ""
            return decryptPassword(secretKey, iv, encrypted)
        } else {
            val password = generatePassword()
            val (secretKey, iv, encrypted) = generateSecretKeyAndEncryptPassword(keyAlias, password)
            KeyPasswordStorage.storeEncryptedPassword(context, keyId, iv, encrypted)
            return password
        }
    }

    private fun generateSecretKeyAndEncryptPassword(keyAlias: String, password: String): Triple<SecretKey, ByteArray, ByteArray> {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(keyGenParameterSpec)
        val secretKey = keyGenerator.generateKey()
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Triple(secretKey, iv, encrypted)
    }

    private fun decryptPassword(secretKey: SecretKey, iv: ByteArray, encrypted: ByteArray): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val spec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val plain = cipher.doFinal(encrypted)
        return String(plain, Charsets.UTF_8)
    }

    private fun generatePassword(): String {
        val random = SecureRandom()
        return (1..PASSWORD_LENGTH)
            .map { CHARSET[random.nextInt(CHARSET.length)] }
            .joinToString("")
    }

    // generateSecretKey больше не используется

    // encryptAndStorePassword больше не используется

    // decryptPassword(secretKey) больше не используется
}

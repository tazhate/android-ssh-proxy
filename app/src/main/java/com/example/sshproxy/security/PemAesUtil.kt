package com.example.sshproxy.security

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object PemAesUtil {
    private const val AES_MODE = "AES/CBC/PKCS5Padding"
    private const val KEY_LENGTH = 256
    private const val ITERATION_COUNT = 100_000
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 16
    private const val PASSWORD_LENGTH = 100
    private const val CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+[]{};:,.<>/?|"

    fun generatePassword(): String {
        val random = SecureRandom()
        return (1..PASSWORD_LENGTH)
            .map { CHARSET[random.nextInt(CHARSET.length)] }
            .joinToString("")
    }

    fun encryptPem(pem: String, password: String): String {
        val salt = ByteArray(SALT_LENGTH)
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(salt)
        SecureRandom().nextBytes(iv)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(pem.toByteArray(Charsets.UTF_8))
        // Формат: salt:iv:ciphertext (все base64)
        return Base64.encodeToString(salt, Base64.NO_WRAP) + ":" +
               Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
               Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    fun decryptPem(encrypted: String, password: String): String {
        val parts = encrypted.split(":")
        require(parts.size == 3) { "Invalid encrypted PEM format" }
        val salt = Base64.decode(parts[0], Base64.NO_WRAP)
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        val plain = cipher.doFinal(ciphertext)
        return String(plain, Charsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }
}

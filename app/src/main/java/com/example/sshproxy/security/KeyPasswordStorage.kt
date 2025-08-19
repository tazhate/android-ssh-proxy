package com.example.sshproxy.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

object KeyPasswordStorage {
    // Для хранения зашифрованного PEM-файла (salt:iv:ciphertext в одной строке)
    private const val PEM_PREFIX = "pem_"
    fun storeEncryptedPem(context: Context, keyId: String, encryptedPem: String) {
        getPrefs(context).edit().putString(PEM_PREFIX + keyId, encryptedPem).apply()
    }
    fun getEncryptedPem(context: Context, keyId: String): String? {
        return getPrefs(context).getString(PEM_PREFIX + keyId, null)
    }
    private const val PREFS_NAME = "pem_key_password_storage"
    private const val KEY_IV_PREFIX = "iv_"
    private const val KEY_ENC_PREFIX = "enc_"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun storeEncryptedPassword(context: Context, keyId: String, iv: ByteArray, encrypted: ByteArray) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putString(KEY_IV_PREFIX + keyId, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(KEY_ENC_PREFIX + keyId, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun getEncryptedPassword(context: Context, keyId: String): Pair<ByteArray, ByteArray>? {
        val prefs = getPrefs(context)
        val ivB64 = prefs.getString(KEY_IV_PREFIX + keyId, null)
        val encB64 = prefs.getString(KEY_ENC_PREFIX + keyId, null)
        if (ivB64 != null && encB64 != null) {
            return Pair(
                Base64.decode(ivB64, Base64.NO_WRAP),
                Base64.decode(encB64, Base64.NO_WRAP)
            )
        }
        return null
    }
}

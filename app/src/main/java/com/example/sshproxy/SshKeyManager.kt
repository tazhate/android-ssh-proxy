package com.example.sshproxy

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.File
import java.io.FileWriter
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class SshKeyManager(private val context: Context) {

    companion object {
        private const val TAG = "SshKeyManager"
        private const val KEY_SIZE = 2048
        private const val PRIVATE_KEY_FILE = "ssh_private_key"
        private const val PUBLIC_KEY_FILE = "ssh_public_key"

        init {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    fun generateKeyPair() {
        try {
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(KEY_SIZE)
            val keyPair = keyGen.generateKeyPair()

            saveKeyPair(keyPair)
            Log.d(TAG, "SSH key pair generated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate key pair", e)
            throw e
        }
    }

    private fun saveKeyPair(keyPair: KeyPair) {
        // Save private key in PEM format
        val privateKeyFile = File(context.filesDir, PRIVATE_KEY_FILE)
        PemWriter(FileWriter(privateKeyFile)).use { pemWriter ->
            pemWriter.writeObject(PemObject("PRIVATE KEY", keyPair.private.encoded))
        }

        // Save public key
        val publicKeyFile = File(context.filesDir, PUBLIC_KEY_FILE)
        publicKeyFile.writeBytes(keyPair.public.encoded)
    }

    fun hasKeyPair(): Boolean {
        val privateKeyFile = File(context.filesDir, PRIVATE_KEY_FILE)
        val publicKeyFile = File(context.filesDir, PUBLIC_KEY_FILE)
        return privateKeyFile.exists() && publicKeyFile.exists()
    }

    fun getPrivateKey(): PrivateKey? {
        return try {
            val privateKeyFile = File(context.filesDir, PRIVATE_KEY_FILE)
            if (!privateKeyFile.exists()) return null

            val keyBytes = privateKeyFile.readBytes()
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val keyFactory = java.security.KeyFactory.getInstance("RSA")
            keyFactory.generatePrivate(keySpec)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load private key", e)
            null
        }
    }

    fun getPublicKey(): String {
        return try {
            val publicKeyFile = File(context.filesDir, PUBLIC_KEY_FILE)
            if (!publicKeyFile.exists()) return "No key found"

            val keyBytes = publicKeyFile.readBytes()
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = java.security.KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(keySpec) as RSAPublicKey

            // Convert to SSH format
            formatSshPublicKey(publicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load public key", e)
            "Error loading key"
        }
    }

    private fun formatSshPublicKey(publicKey: RSAPublicKey): String {
        val sshRsaBytes = "ssh-rsa".toByteArray()
        val exponent = publicKey.publicExponent.toByteArray()
        val modulus = publicKey.modulus.toByteArray()

        val totalLength = 4 + sshRsaBytes.size + 4 + exponent.size + 4 + modulus.size
        val result = ByteArray(totalLength)
        var position = 0

        // Add "ssh-rsa" string
        result[position++] = (sshRsaBytes.size shr 24).toByte()
        result[position++] = (sshRsaBytes.size shr 16).toByte()
        result[position++] = (sshRsaBytes.size shr 8).toByte()
        result[position++] = sshRsaBytes.size.toByte()
        System.arraycopy(sshRsaBytes, 0, result, position, sshRsaBytes.size)
        position += sshRsaBytes.size

        // Add exponent
        result[position++] = (exponent.size shr 24).toByte()
        result[position++] = (exponent.size shr 16).toByte()
        result[position++] = (exponent.size shr 8).toByte()
        result[position++] = exponent.size.toByte()
        System.arraycopy(exponent, 0, result, position, exponent.size)
        position += exponent.size

        // Add modulus
        result[position++] = (modulus.size shr 24).toByte()
        result[position++] = (modulus.size shr 16).toByte()
        result[position++] = (modulus.size shr 8).toByte()
        result[position++] = modulus.size.toByte()
        System.arraycopy(modulus, 0, result, position, modulus.size)

        return "ssh-rsa " + Base64.encodeToString(result, Base64.NO_WRAP)
    }

    fun getPrivateKeyFile(): File {
        return File(context.filesDir, PRIVATE_KEY_FILE)
    }
}


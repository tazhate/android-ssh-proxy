package com.example.sshproxy.data

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileWriter
import java.security.*
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.*

class SshKeyManager(private val context: Context) {

    companion object {
        private const val TAG = "SshKeyManager"
        private const val KEY_SIZE = 2048
        private const val PRIVATE_KEY_PREFIX = "ssh_private_"
        private const val PUBLIC_KEY_PREFIX = "ssh_public_"

        init {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    fun generateKeyPair(name: String): SshKey {
        try {
            val keyId = UUID.randomUUID().toString()
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(KEY_SIZE)
            val keyPair = keyGen.generateKeyPair()

            saveKeyPair(keyPair, keyId)
            Log.d(TAG, "SSH key pair generated successfully for $name")
            
            val publicKeyString = formatSshPublicKey(keyPair.public as RSAPublicKey)
            val fingerprint = generateFingerprint(keyPair.public)
            
            return SshKey(keyId, name, publicKeyString, fingerprint)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate key pair", e)
            throw e
        }
    }

    private fun saveKeyPair(keyPair: KeyPair, keyId: String) {
        val privateKeyFile = File(context.filesDir, "$PRIVATE_KEY_PREFIX$keyId")
        PemWriter(FileWriter(privateKeyFile)).use { pemWriter ->
            pemWriter.writeObject(PemObject("PRIVATE KEY", keyPair.private.encoded))
        }

        val publicKeyFile = File(context.filesDir, "$PUBLIC_KEY_PREFIX$keyId")
        publicKeyFile.writeBytes(keyPair.public.encoded)
    }

    fun hasKeyPair(): Boolean {
        return context.filesDir.listFiles { _, name -> name.startsWith(PRIVATE_KEY_PREFIX) }?.isNotEmpty() ?: false
    }

    fun getPublicKey(keyId: String): String? {
        return try {
            val publicKeyFile = File(context.filesDir, "$PUBLIC_KEY_PREFIX$keyId")
            if (!publicKeyFile.exists()) return null

            val keyBytes = publicKeyFile.readBytes()
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(keySpec) as RSAPublicKey
            formatSshPublicKey(publicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load public key", e)
            null
        }
    }

    private fun formatSshPublicKey(publicKey: RSAPublicKey): String {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        try {
            val sshRsa = "ssh-rsa".toByteArray()
            dos.writeInt(sshRsa.size)
            dos.write(sshRsa)

            val e = publicKey.publicExponent.toByteArray()
            dos.writeInt(e.size)
            dos.write(e)

            val m = publicKey.modulus.toByteArray()
            dos.writeInt(m.size)
            dos.write(m)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to format public key", e)
            return "Error formatting key"
        }
        return "ssh-rsa " + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
    
    private fun generateFingerprint(publicKey: PublicKey): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(publicKey.encoded)
            val hexString = StringBuilder()
            for (i in digest.indices) {
                val hex = Integer.toHexString(0xFF and digest[i].toInt())
                if (hex.length == 1) {
                    hexString.append('0')
                }
                hexString.append(hex)
                if (i < digest.size - 1) {
                    hexString.append(":")
                }
            }
            "MD5:$hexString"
        } catch (e: Exception) {
            "Error generating fingerprint"
        }
    }

    fun getPrivateKeyFile(keyId: String): File {
        return File(context.filesDir, "$PRIVATE_KEY_PREFIX$keyId")
    }
    
    fun deleteKeyFiles(keyId: String) {
        File(context.filesDir, "$PRIVATE_KEY_PREFIX$keyId").delete()
        File(context.filesDir, "$PUBLIC_KEY_PREFIX$keyId").delete()
    }
}
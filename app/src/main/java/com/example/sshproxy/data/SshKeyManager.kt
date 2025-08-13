package com.example.sshproxy.data

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.sshproxy.security.KeystoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.bouncycastle.jcajce.provider.asymmetric.edec.BCEdDSAPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.StringWriter
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.interfaces.EdECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.ECGenParameterSpec
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*

class SshKeyManager(private val context: Context, private val keyRepository: KeyRepository) {

    enum class KeyType(val algorithm: String, val spec: Any?, val sshName: String) {
        RSA("RSA", 4096, "ssh-rsa"),
        ED25519("Ed25519", NamedParameterSpec.ED25519, "ssh-ed25519"),
        ECDSA_256("EC", ECGenParameterSpec("secp256r1"), "ecdsa-sha2-nistp256")
    }

    companion object {
        private const val TAG = "SshKeyManager"
        private const val PRIVATE_KEY_PREFIX = "ssh_private_encrypted_"
        private const val IV_PREFIX = "ssh_iv_"
        private const val PUBLIC_KEY_PREFIX = "ssh_public_"
        val DEFAULT_KEY_TYPE = KeyType.ECDSA_256

        init {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    private val keystoreManager = KeystoreManager()

    suspend fun hasKeyPair(): Boolean = withContext(Dispatchers.IO) {
        keyRepository.getAllKeys().first().isNotEmpty()
    }


    suspend fun generateKeyPair(name: String, keyType: KeyType = DEFAULT_KEY_TYPE): SshKey = withContext(Dispatchers.IO) {
        try {
            val keyId = UUID.randomUUID().toString()
            val keyGen = KeyPairGenerator.getInstance(keyType.algorithm)
            when (val spec = keyType.spec) {
                is Int -> keyGen.initialize(spec)
                is AlgorithmParameterSpec -> keyGen.initialize(spec)
            }
            val keyPair = keyGen.generateKeyPair()

            saveKeyPair(keyPair, keyId)
            Log.d(TAG, "SSH key pair ($keyType) generated successfully for $name")

            val publicKeyString = formatSshPublicKey(keyPair.public, keyType)
            val fingerprint = generateFingerprint(keyPair.public)

            val newKey = SshKey(
                id = keyId,
                name = name,
                publicKey = publicKeyString,
                fingerprint = fingerprint,
                keyType = keyType
            )
            keyRepository.insertKey(newKey)
            newKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate key pair", e)
            throw e
        }
    }

    private fun saveKeyPair(keyPair: KeyPair, keyId: String) {
        // Save private key encrypted using Android Keystore
        val privateKeyPem = convertPrivateKeyToPem(keyPair.private)
        val encryptedData = keystoreManager.encryptPrivateKey(keyId, privateKeyPem.toByteArray())
        
        val privateKeyFile = File(context.filesDir, "$PRIVATE_KEY_PREFIX$keyId")
        privateKeyFile.writeBytes(encryptedData.ciphertext)
        
        val ivFile = File(context.filesDir, "$IV_PREFIX$keyId")
        ivFile.writeBytes(encryptedData.iv)

        // Save public key unencrypted (it's public anyway)
        val publicKeyFile = File(context.filesDir, "$PUBLIC_KEY_PREFIX$keyId")
        publicKeyFile.writeBytes(keyPair.public.encoded)
    }

    private fun convertPrivateKeyToPem(privateKey: PrivateKey): String {
        val stringWriter = StringWriter()
        PemWriter(stringWriter).use { pemWriter ->
            pemWriter.writeObject(PemObject("PRIVATE KEY", privateKey.encoded))
        }
        return stringWriter.toString()
    }

    suspend fun getPublicKey(keyId: String): String? = withContext(Dispatchers.IO) {
        try {
            val key = keyRepository.getKeyById(keyId)
            if (key == null) {
                Log.e(TAG, "Key with id $keyId not found in database")
                return@withContext null
            }

            val publicKeyFile = File(context.filesDir, "$PUBLIC_KEY_PREFIX$keyId")
            if (!publicKeyFile.exists()) {
                Log.e(TAG, "Public key file for keyId $keyId not found")
                return@withContext null
            }

            val keyBytes = publicKeyFile.readBytes()
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance(key.keyType.algorithm)
            val publicKey = keyFactory.generatePublic(keySpec)

            formatSshPublicKey(publicKey, key.keyType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load public key", e)
            null
        }
    }

    suspend fun getActivePublicKey(): String = withContext(Dispatchers.IO) {
        try {
            val preferencesManager = PreferencesManager(context)
            val activeKeyId = preferencesManager.getActiveKeyId()

            if (activeKeyId != null) {
                getPublicKey(activeKeyId) ?: ""
            } else {
                // If no active key set, try to get the first available key
                val keys = keyRepository.getAllKeys().first()
                if (keys.isNotEmpty()) {
                    val firstKey = keys.first()
                    preferencesManager.setActiveKeyId(firstKey.id)
                    firstKey.publicKey
                } else {
                    ""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get active public key", e)
            ""
        }
    }

    private fun formatSshPublicKey(publicKey: PublicKey, keyType: KeyType): String {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        try {
            dos.writeInt(keyType.sshName.toByteArray().size)
            dos.write(keyType.sshName.toByteArray())

            when (keyType) {
                KeyType.RSA -> {
                    val rsaPublicKey = publicKey as RSAPublicKey
                    val e = rsaPublicKey.publicExponent.toByteArray()
                    dos.writeInt(e.size)
                    dos.write(e)
                    val m = rsaPublicKey.modulus.toByteArray()
                    dos.writeInt(m.size)
                    dos.write(m)
                }
                KeyType.ED25519 -> {
                    val edPublicKey = publicKey as BCEdDSAPublicKey
                    val p = edPublicKey.pointEncoding.reversedArray() // Little-endian
                    dos.writeInt(p.size)
                    dos.write(p)
                }
                KeyType.ECDSA_256 -> {
                    val ecPublicKey = publicKey as ECPublicKey
                    val curveName = "nistp256"
                    dos.writeInt(curveName.toByteArray().size)
                    dos.write(curveName.toByteArray())
                    val p = ecPublicKey.w.affineX.toByteArray(32) + ecPublicKey.w.affineY.toByteArray(32)
                    val uncompressed = ByteArray(1) { 4 } + p
                    dos.writeInt(uncompressed.size)
                    dos.write(uncompressed)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to format public key for keyType: $keyType", e)
            return "Error formatting key: ${e.message}"
        }
        return "${keyType.sshName} " + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun generateFingerprint(publicKey: PublicKey): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(buildSshPublicKey(publicKey))
            "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING).removeSuffix("=")
        } catch (e: Exception) {
            "Error generating fingerprint"
        }
    }

    fun getPrivateKeyFile(keyId: String): File {
        return File(context.filesDir, "$PRIVATE_KEY_PREFIX$keyId")
    }

    /**
     * Get decrypted private key for SSH operations
     * @param keyId SSH key identifier
     * @return PrivateKey object for SSH connections
     */
    suspend fun getPrivateKey(keyId: String): PrivateKey? = withContext(Dispatchers.IO) {
        try {
            val key = keyRepository.getKeyById(keyId) ?: return@withContext null
            
            val privateKeyFile = File(context.filesDir, "$PRIVATE_KEY_PREFIX$keyId")
            val ivFile = File(context.filesDir, "$IV_PREFIX$keyId")
            
            if (!privateKeyFile.exists() || !ivFile.exists()) {
                Log.e(TAG, "Private key or IV file not found for keyId: $keyId")
                return@withContext null
            }
            
            val ciphertext = privateKeyFile.readBytes()
            val iv = ivFile.readBytes()
            val encryptedData = KeystoreManager.EncryptedData(ciphertext, iv)
            
            val decryptedPem = keystoreManager.decryptPrivateKey(keyId, encryptedData)
            val pemString = String(decryptedPem)
            
            // Parse PEM to get PrivateKey object
            return@withContext parsePemToPrivateKey(pemString, key.keyType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt private key for keyId: $keyId", e)
            null
        }
    }

    private fun parsePemToPrivateKey(pemString: String, keyType: KeyType): PrivateKey? {
        try {
            // Extract base64 content from PEM
            val base64Content = pemString
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\n", "")
                .replace("\r", "")
            
            val keyBytes = Base64.decode(base64Content, Base64.DEFAULT)
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance(keyType.algorithm)
            return keyFactory.generatePrivate(keySpec)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PEM to private key", e)
            return null
        }
    }

    fun deleteKeyFiles(keyId: String) {
        File(context.filesDir, "$PRIVATE_KEY_PREFIX$keyId").delete()
        File(context.filesDir, "$IV_PREFIX$keyId").delete()
        File(context.filesDir, "$PUBLIC_KEY_PREFIX$keyId").delete()
        // Also delete the encryption key from Android Keystore
        keystoreManager.deleteEncryptionKey(keyId)
    }

    private fun buildSshPublicKey(key: PublicKey): ByteArray {
        val byteos = ByteArrayOutputStream()
        val dos = DataOutputStream(byteos)

        when (key) {
            is RSAPublicKey -> {
                val sshName = "ssh-rsa".toByteArray()
                dos.writeInt(sshName.size)
                dos.write(sshName)
                dos.writeInt(key.publicExponent.toByteArray().size)
                dos.write(key.publicExponent.toByteArray())
                dos.writeInt(key.modulus.toByteArray().size)
                dos.write(key.modulus.toByteArray())
            }
            is EdECPublicKey -> {
                val sshName = "ssh-ed25519".toByteArray()
                dos.writeInt(sshName.size)
                dos.write(sshName)
                val p = key.point.y.toByteArray().reversedArray()
                dos.writeInt(p.size)
                dos.write(p)
            }
            is ECPublicKey -> {
                val sshName = "ecdsa-sha2-nistp256".toByteArray()
                dos.writeInt(sshName.size)
                dos.write(sshName)
                val curveName = "nistp256".toByteArray()
                dos.writeInt(curveName.size)
                dos.write(curveName)
                val p = key.w.affineX.toByteArray(32) + key.w.affineY.toByteArray(32)
                val uncompressed = ByteArray(1) { 4 } + p
                dos.writeInt(uncompressed.size)
                dos.write(uncompressed)
            }
        }
        return byteos.toByteArray()
    }

    // Helper to pad byte array to a specific size
    private fun BigInteger.toByteArray(size: Int): ByteArray {
        val bytes = toByteArray()
        if (bytes.size == size) return bytes
        val padded = ByteArray(size)
        val offset = if (bytes.size == size + 1 && bytes[0].toInt() == 0) 1 else 0
        val length = bytes.size - offset
        if (length > size) {
            throw IllegalStateException("BigInteger is too large to fit in $size bytes")
        }
        System.arraycopy(bytes, offset, padded, size - length, length)
        return padded
    }
}
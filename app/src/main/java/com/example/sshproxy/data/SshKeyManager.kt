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

import android.annotation.SuppressLint

class SshKeyManager(private val context: Context, private val keyRepository: KeyRepository) {
    // Сохраняет зашифрованный PEM-файл приватного ключа
    fun saveEncryptedPem(keyId: String, pem: String) {
        val password = com.example.sshproxy.security.KeyPasswordKeystore.getOrCreatePassword(context, keyId)
        val encryptedPem = com.example.sshproxy.security.PemAesUtil.encryptPem(pem, password)
        com.example.sshproxy.security.KeyPasswordStorage.storeEncryptedPem(context, keyId, encryptedPem)
    }

    // Загружает и расшифровывает PEM-файл приватного ключа
    fun loadDecryptedPem(keyId: String): String? {
        Log.d(TAG, "loadDecryptedPem called for keyId: $keyId")
        val encryptedPem = com.example.sshproxy.security.KeyPasswordStorage.getEncryptedPem(context, keyId)
        Log.d(TAG, "Encrypted PEM from storage: ${if (encryptedPem != null) "Found (${encryptedPem.length} chars)" else "NULL"}")
        
        val password = com.example.sshproxy.security.KeyPasswordKeystore.getOrCreatePassword(context, keyId)
        Log.d(TAG, "Password from Keystore: ${if (password != null) "Found" else "NULL"}")
        
        return if (encryptedPem != null && password != null) {
            try {
                val decrypted = com.example.sshproxy.security.PemAesUtil.decryptPem(encryptedPem, password)
                Log.d(TAG, "PEM decryption successful: ${if (decrypted != null) decrypted.length else 0} chars")
                decrypted
            } catch (e: Exception) { 
                Log.e(TAG, "PEM decryption failed", e)
                null 
            }
        } else {
            Log.e(TAG, "Cannot decrypt: encryptedPem=${encryptedPem != null}, password=${password != null}")
            null
        }
    }

    @SuppressLint("NewApi")
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

            // --- Новый способ: шифруем PEM паролем из Keystore, сохраняем IV+encrypted в SharedPreferences ---
            val privateKeyPem = convertPrivateKeyToPem(keyPair.private)
            val password = com.example.sshproxy.security.KeyPasswordKeystore.getOrCreatePassword(context, keyId)
            val encryptedPem = com.example.sshproxy.security.PemAesUtil.encryptPem(privateKeyPem, password)
            com.example.sshproxy.security.KeyPasswordStorage.storeEncryptedPem(context, keyId, encryptedPem)

            // Сохраняем публичный ключ (старый способ)
            val publicKeyFile = File(context.filesDir, "$PUBLIC_KEY_PREFIX$keyId")
            publicKeyFile.writeBytes(keyPair.public.encoded)

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

    fun convertPrivateKeyToPem(privateKey: PrivateKey): String {
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
            if (keyBytes.isEmpty()) {
                Log.e(TAG, "Public key file for keyId $keyId is empty")
                return@withContext null
            }
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance(key.keyType.algorithm)
            val publicKey = try { keyFactory.generatePublic(keySpec) } catch (e: Exception) {
                Log.e(TAG, "Failed to generate public key from spec", e)
                return@withContext null
            }
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
        if (publicKey == null) {
            Log.e(TAG, "formatSshPublicKey: publicKey is null")
            return "Error: publicKey is null"
        }
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        try {
            val sshNameBytes = keyType.sshName.toByteArray()
            dos.writeInt(sshNameBytes.size)
            dos.write(sshNameBytes)

            when (keyType) {
                KeyType.RSA -> {
                    val rsaPublicKey = publicKey as? RSAPublicKey
                    if (rsaPublicKey == null) return "Error: Not an RSA public key"
                    val e = rsaPublicKey.publicExponent?.toByteArray() ?: return "Error: RSA exponent is null"
                    val m = rsaPublicKey.modulus?.toByteArray() ?: return "Error: RSA modulus is null"
                    dos.writeInt(e.size)
                    dos.write(e)
                    dos.writeInt(m.size)
                    dos.write(m)
                }
                KeyType.ED25519 -> {
                    val edPublicKey = publicKey as? BCEdDSAPublicKey
                    if (edPublicKey == null) return "Error: Not an ED25519 public key"
                    val p = edPublicKey.pointEncoding?.reversedArray() ?: return "Error: ED25519 pointEncoding is null"
                    dos.writeInt(p.size)
                    dos.write(p)
                }
                KeyType.ECDSA_256 -> {
                    val ecPublicKey = publicKey as? ECPublicKey
                    if (ecPublicKey == null) return "Error: Not an ECDSA public key"
                    val curveName = "nistp256"
                    val curveNameBytes = curveName.toByteArray()
                    dos.writeInt(curveNameBytes.size)
                    dos.write(curveNameBytes)
                    val x = ecPublicKey.w.affineX?.toByteArray(32) ?: return "Error: ECDSA X is null"
                    val y = ecPublicKey.w.affineY?.toByteArray(32) ?: return "Error: ECDSA Y is null"
                    val p = x + y
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
            Log.d(TAG, "getPrivateKey called for keyId: $keyId")
            val key = keyRepository.getKeyById(keyId)
            if (key == null) {
                Log.e(TAG, "Key not found in database for keyId: $keyId")
                return@withContext null
            }
            Log.d(TAG, "Key found in database: ${key.name}, type: ${key.keyType}")
            
            // 1. Пробуем зашифрованный PEM-файл с паролем из Keystore
            val pemString = loadDecryptedPem(keyId)
            Log.d(TAG, "loadDecryptedPem returned: ${if (pemString != null) "PEM content (${pemString.length} chars)" else "null"}")
            
            if (pemString != null) {
                val privateKey = parsePemToPrivateKey(pemString, key.keyType)
                Log.d(TAG, "parsePemToPrivateKey returned: ${if (privateKey != null) "PrivateKey object" else "null"}")
                return@withContext privateKey
            }
            Log.e(TAG, "No private key found for keyId: $keyId")
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get private key for keyId: $keyId", e)
            null
        }
    }

    private fun parsePemToPrivateKey(pemString: String, keyType: KeyType): PrivateKey? {
        try {
            Log.d(TAG, "parsePemToPrivateKey: keyType=${keyType.algorithm}, pemLength=${pemString.length}")
            
            // Extract base64 content from PEM
            val base64Content = pemString
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\n", "")
                .replace("\r", "")
            
            Log.d(TAG, "Base64 content length: ${base64Content.length}")
            
            val keyBytes = Base64.decode(base64Content, Base64.DEFAULT)
            Log.d(TAG, "Decoded key bytes: ${keyBytes.size} bytes")
            
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance(keyType.algorithm)
            val privateKey = keyFactory.generatePrivate(keySpec)
            
            Log.d(TAG, "Successfully created PrivateKey: ${privateKey.algorithm}")
            return privateKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PEM to private key for keyType: ${keyType.algorithm}", e)
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
            else -> {
                // EdECPublicKey поддерживается только с API 33
                if (android.os.Build.VERSION.SDK_INT >= 33 && key is EdECPublicKey) {
                    val sshName = "ssh-ed25519".toByteArray()
                    dos.writeInt(sshName.size)
                    dos.write(sshName)
                    val p = key.point.y.toByteArray().reversedArray()
                    dos.writeInt(p.size)
                    dos.write(p)
                }
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
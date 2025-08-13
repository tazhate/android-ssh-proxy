package com.example.sshproxy.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.security.PrivateKey

class KeyRepository(private val context: Context) {
    private val database = ServerDatabase.getDatabase(context)
    private val keyDao = database.keyDao()

    fun getAllKeys(): Flow<List<SshKey>> = keyDao.getAllKeys()

    suspend fun insertKey(key: SshKey) {
        withContext(Dispatchers.IO) {
            keyDao.insertKey(key)
        }
    }

    suspend fun getKeyById(id: String): SshKey? {
        return withContext(Dispatchers.IO) {
            keyDao.getKeyById(id)
        }
    }

    suspend fun generateKeyPair(name: String) {
        withContext(Dispatchers.IO) {
            SshKeyManager(context, this@KeyRepository).generateKeyPair(name)
        }
    }

    suspend fun deleteKey(id: String) {
        withContext(Dispatchers.IO) {
            keyDao.deleteKey(id)
            SshKeyManager(context, this@KeyRepository).deleteKeyFiles(id)
        }
    }

    /**
     * Get decrypted private key for SSH operations
     * @param id SSH key identifier
     * @return PrivateKey object for SSH connections, null if not found or decryption failed
     */
    suspend fun getPrivateKey(id: String): PrivateKey? {
        return withContext(Dispatchers.IO) {
            SshKeyManager(context, this@KeyRepository).getPrivateKey(id)
        }
    }
}
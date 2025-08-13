package com.example.sshproxy.data

import android.content.Context
import com.example.sshproxy.security.SecureHostKeyVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ServerRepository(private val context: Context) {
    private val database = ServerDatabase.getDatabase(context)
    private val serverDao = database.serverDao()
    private val hostKeyVerifier by lazy { SecureHostKeyVerifier(context) }

    fun getAllServers(): Flow<List<Server>> = serverDao.getAllServers()
    
    fun getFavoriteServers(): Flow<List<Server>> = serverDao.getFavoriteServers()
    
    suspend fun insertServer(server: Server) {
        withContext(Dispatchers.IO) {
            serverDao.insertServer(server)
        }
    }
    
    suspend fun deleteServer(server: Server) {
        withContext(Dispatchers.IO) {
            serverDao.deleteServer(server)
        }
    }
    
    suspend fun getServerById(id: Long): Server? {
        return withContext(Dispatchers.IO) {
            serverDao.getServerById(id)
        }
    }

    /**
     * Get stored fingerprint for a server
     * @param server The server to get fingerprint for
     * @return Server fingerprint or null if not stored
     */
    fun getServerFingerprint(server: Server): String? {
        return hostKeyVerifier.getServerFingerprint(server.host, server.port)
    }
}

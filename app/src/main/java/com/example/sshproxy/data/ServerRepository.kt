package com.example.sshproxy.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ServerRepository(context: Context) {
    private val database = ServerDatabase.getDatabase(context)
    private val serverDao = database.serverDao()

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
}

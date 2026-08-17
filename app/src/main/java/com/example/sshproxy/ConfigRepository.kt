package com.example.sshproxy.data

import kotlinx.coroutines.flow.Flow

class ConfigRepository(private val configDao: ConfigDao) {

    fun getLatestConfig(): Flow<ConfigEntity?> = configDao.getLatestConfig()

    suspend fun saveConfig(config: ConfigEntity) {
        configDao.insert(config)
    }

    suspend fun clearConfig() {
        configDao.deleteAll()
    }
}
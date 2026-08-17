package com.example.sshproxy.data

import kotlinx.coroutines.flow.Flow

class ConfigRepository(private val configDao: ConfigDao) {

    // Uses your actual DAO method: getLatest()
    suspend fun getLatest(): ConfigEntity? = configDao.getLatest()

    suspend fun saveConfig(config: ConfigEntity) {
        configDao.insert(config)
    }

    suspend fun clearConfig() {
        configDao.deleteAll()
    }
}
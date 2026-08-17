package com.example.sshproxy.data

class ConfigRepository(private val configDao: ConfigDao) {

    suspend fun getLatest(): ConfigEntity? = configDao.getLatest()

    suspend fun saveConfig(config: ConfigEntity) {
        configDao.insert(config)
    }

    // If you need to clear all configs, add this method to ConfigDao first.
    // suspend fun clearConfig() = configDao.deleteAll()
}
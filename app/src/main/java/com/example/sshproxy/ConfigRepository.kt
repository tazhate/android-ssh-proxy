package com.example.sshproxy

import com.example.sshproxy.data.ConfigDatabase
import com.example.sshproxy.data.ConfigEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ConfigRepository(private val context: android.content.Context) {

    private val database = ConfigDatabase.getDatabase(context)
    private val dao = database.configDao()

    fun saveConfig(sshDetails: String, proxyHost: String, proxyPort: String, payload: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val entity = ConfigEntity(
                sshDetails = sshDetails,
                proxyHost = proxyHost,
                proxyPort = proxyPort,
                payload = payload
            )
            dao.insert(entity)
        }
    }

    fun loadLatestConfig(callback: (ConfigEntity?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val config = dao.getLatest()
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                callback(config)
            }
        }
    }
}

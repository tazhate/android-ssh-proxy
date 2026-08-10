package com.example.sshproxy

import com.example.sshproxy.data.ConfigDatabase
import com.example.sshproxy.data.ConfigEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ConfigRepository(private val context: android.content.Context) {

    private val database = ConfigDatabase.getDatabase(context)
    private val dao = database.configDao()

    // Updated to match new entity fields
    fun saveConfig(
        sshDetails: String,
        proxyInput: String,      // single field
        payload: String,
        splitDelay: Int,
        dnsServer: String,
        pingTarget: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val entity = ConfigEntity(
                sshDetails = sshDetails,
                proxyInput = proxyInput,
                payload = payload,
                splitDelay = splitDelay,
                dnsServer = dnsServer,
                pingTarget = pingTarget
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

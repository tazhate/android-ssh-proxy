package com.example.sshproxy

import com.example.sshproxy.data.ConfigDatabase
import com.example.sshproxy.data.ConfigEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ConfigRepository(private val context: android.content.Context) {

    private val database = ConfigDatabase.getDatabase(context)
    private val dao = database.configDao()

    fun saveConfig(
        sshDetails: String,
        proxyInput: String,
        payload: String,
        splitDelay: Int,
        dnsServer: String,
        pingTarget: String,
        enableCompression: Boolean,
        mtu: Int,
        sendBuffer: Int,
        receiveBuffer: Int,
        pingUrl: String,
        pingInterval: Int,
        pingTimeout: Int,
        alwaysReconnect: Boolean
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val entity = ConfigEntity(
                sshDetails = sshDetails,
                proxyInput = proxyInput,
                payload = payload,
                splitDelay = splitDelay,
                dnsServer = dnsServer,
                pingTarget = pingTarget,
                enableCompression = enableCompression,
                mtu = mtu,
                sendBuffer = sendBuffer,
                receiveBuffer = receiveBuffer,
                pingUrl = pingUrl,
                pingInterval = pingInterval,
                pingTimeout = pingTimeout,
                alwaysReconnect = alwaysReconnect
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

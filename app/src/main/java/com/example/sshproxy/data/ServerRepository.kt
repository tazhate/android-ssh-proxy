package com.example.sshproxy.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ServerRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("servers_v2", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getServers(): List<Server> {
        val json = prefs.getString("server_list", "[]")
        val type = object : TypeToken<List<Server>>() {}.type
        return gson.fromJson(json, type)
    }

    fun addServer(server: Server) {
        val servers = getServers().toMutableList()
        servers.add(server)
        saveServers(servers)
    }

    fun updateServer(server: Server) {
        val servers = getServers().toMutableList()
        val index = servers.indexOfFirst { it.id == server.id }
        if (index >= 0) {
            servers[index] = server
            saveServers(servers)
        }
    }

    fun removeServer(serverId: String) {
        val servers = getServers().toMutableList()
        servers.removeAll { it.id == serverId }
        saveServers(servers)
    }

    private fun saveServers(servers: List<Server>) {
        val json = gson.toJson(servers)
        prefs.edit().putString("server_list", json).apply()
    }
}

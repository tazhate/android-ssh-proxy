package com.example.sshproxy

import android.content.Context

class ServerManager(context: Context) {
    private val prefs = context.getSharedPreferences("ssh_servers", Context.MODE_PRIVATE)
    private val serversKey = "server_list"

    fun getServers(): List<Server> {
        return prefs.getStringSet(serversKey, setOf("37.27.203.248:22"))
            ?.mapNotNull { Server.fromString(it) }
            ?.sortedBy { it.host } ?: emptyList()
    }

    fun addServer(server: Server) {
        val servers = getServers().toMutableSet()
        servers.add(server)
        saveServers(servers.map { it.toString() }.toSet())
    }

    fun removeServer(server: Server) {
        val servers = getServers().toMutableSet()
        servers.remove(server)
        saveServers(servers.map { it.toString() }.toSet())
    }

    private fun saveServers(servers: Set<String>) {
        prefs.edit().putStringSet(serversKey, servers).apply()
    }
}

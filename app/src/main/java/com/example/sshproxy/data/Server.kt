package com.example.sshproxy.data

import java.util.UUID

data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String = "user"
) {
    companion object {
        fun fromLegacyString(str: String): Server? {
            val parts = str.split(":")
            return if (parts.size == 2) {
                Server(
                    name = parts[0],
                    host = parts[0],
                    port = parts[1].toIntOrNull() ?: 22
                )
            } else null
        }
    }
}

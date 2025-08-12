package com.example.sshproxy

data class Server(val host: String, val port: Int) {
    override fun toString(): String {
        return "$host:$port"
    }

    companion object {
        fun fromString(serverString: String): Server? {
            val parts = serverString.split(":")
            return if (parts.size == 2) {
                val host = parts[0]
                val port = parts[1].toIntOrNull() ?: 22
                if (host.isNotBlank()) Server(host, port) else null
            } else {
                null
            }
        }
    }
}

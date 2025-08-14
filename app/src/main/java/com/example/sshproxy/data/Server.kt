package com.example.sshproxy.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class Server(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "host")
    val host: String,

    @ColumnInfo(name = "port")
    val port: Int = 22,

    @ColumnInfo(name = "username")
    val username: String = "user",

    @ColumnInfo(name = "http_proxy_port")
    val httpProxyPort: Int = 8080, // HTTP proxy port

    @ColumnInfo(name = "ssh_key_id")
    val sshKeyId: String? = null, // ID SSH ключа для этого сервера

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_used")
    val lastUsed: Long? = null
)

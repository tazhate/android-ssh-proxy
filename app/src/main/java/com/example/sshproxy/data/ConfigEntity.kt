package com.example.sshproxy.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "config_table")
data class ConfigEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val sshDetails: String,          // host:port@user:pass
    val proxyInput: String,          // single field (host:port or Base64)
    val payload: String,
    val splitDelay: Int,
    val dnsServer: String,           // new
    val pingTarget: String
)

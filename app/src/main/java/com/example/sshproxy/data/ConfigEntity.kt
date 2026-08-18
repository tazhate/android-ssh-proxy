package com.example.sshproxy.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "config_table")
data class ConfigEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val sshDetails: String,
    val proxyInput: String,
    val payload: String,
    val splitDelay: Int,
    val dnsServer: String,
    val pingTarget: String,
    val enableCompression: Boolean = true,
    val mtu: Int = 1500,
    val sendBuffer: Int = 16384,
    val receiveBuffer: Int = 32768,
    val pingUrl: String = "https://dns.google",
    val pingInterval: Int = 2000,
    val pingTimeout: Int = 5000,
    val alwaysReconnect: Boolean = false,
    val followRedirects: Boolean = true,
    val enhanced: Boolean = false   // <-- NEW
)
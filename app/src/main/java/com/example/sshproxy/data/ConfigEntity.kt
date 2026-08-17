package com.example.sshproxy.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "config_table")
data class ConfigEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val sshDetails: String = "",
    val remoteProxy: String = "",
    val payload: String = "",
    val splitDelay: Int = 500,
    val dnsPrimary: String = "1.1.1.1",
    val dnsSecondary: String = "1.0.0.1",
    val enhanced: Boolean = false  // <-- ADD THIS
)
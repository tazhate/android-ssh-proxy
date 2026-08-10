package com.example.sshproxy.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "config_table")
data class ConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sshDetails: String,
    val proxyHost: String,
    val proxyPort: String,
    val payload: String,
    val timestamp: Long = System.currentTimeMillis()
)

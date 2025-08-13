package com.example.sshproxy.data

import androidx.room.Entity
import androidx.room.PrimaryKey

import com.example.sshproxy.data.SshKeyManager

@Entity(tableName = "ssh_keys")
data class SshKey(
    @PrimaryKey
    val id: String,
    val name: String,
    val publicKey: String,
    val fingerprint: String,
    val keyType: SshKeyManager.KeyType, // Added keyType
    val createdAt: Long = System.currentTimeMillis()
)

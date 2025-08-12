package com.example.sshproxy.data

data class SshKey(
    val id: String,
    val name: String,
    val publicKey: String,
    val fingerprint: String
)

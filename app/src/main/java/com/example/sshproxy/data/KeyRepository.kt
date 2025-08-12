package com.example.sshproxy.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.*

class KeyRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("ssh_keys", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val keyManager = SshKeyManager(context)

    fun getKeys(): List<SshKey> {
        val json = prefs.getString("key_list", "[]")
        val type = object : TypeToken<List<SshKey>>() {}.type
        return gson.fromJson(json, type)
    }

    fun generateKeyPair(name: String) {
        val key = keyManager.generateKeyPair(name)
        val keys = getKeys().toMutableList()
        keys.add(key)
        saveKeys(keys)
    }

    fun deleteKey(id: String) {
        val keys = getKeys().toMutableList()
        keys.removeAll { it.id == id }
        saveKeys(keys)
        keyManager.deleteKeyFiles(id)
    }

    private fun saveKeys(keys: List<SshKey>) {
        val json = gson.toJson(keys)
        prefs.edit().putString("key_list", json).apply()
    }
}

package com.example.sshproxy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface KeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKey(key: SshKey)

    @Query("SELECT * FROM ssh_keys")
    fun getAllKeys(): Flow<List<SshKey>>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun getKeyById(id: String): SshKey?

    @Query("DELETE FROM ssh_keys WHERE id = :id")
    suspend fun deleteKey(id: String)
}

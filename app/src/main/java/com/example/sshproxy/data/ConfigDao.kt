package com.example.sshproxy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ConfigDao {
    @Insert
    suspend fun insert(config: ConfigEntity)

    @Query("SELECT * FROM config_table ORDER BY id DESC LIMIT 1")
    suspend fun getLatest(): ConfigEntity?
}

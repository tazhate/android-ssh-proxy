package com.example.sshproxy.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: Server)

    @Delete
    suspend fun deleteServer(server: Server)

    @Query("SELECT * FROM servers")
    fun getAllServers(): Flow<List<Server>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getServerById(id: Long): Server?

    @Query("SELECT * FROM servers WHERE is_favorite = 1")
    fun getFavoriteServers(): Flow<List<Server>>
}
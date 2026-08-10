package com.example.sshproxy.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

@Database(entities = [ConfigEntity::class], version = 1, exportSchema = false)
abstract class ConfigDatabase : RoomDatabase() {
    abstract fun configDao(): ConfigDao

    companion object {
        @Volatile
        private var INSTANCE: ConfigDatabase? = null

        fun getDatabase(context: Context): ConfigDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ConfigDatabase::class.java,
                    "config_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

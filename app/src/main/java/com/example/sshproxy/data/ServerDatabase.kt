package com.example.sshproxy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun fromKeyType(value: SshKeyManager.KeyType): String {
        return value.name
    }

    @TypeConverter
    fun toKeyType(value: String): SshKeyManager.KeyType {
        return SshKeyManager.KeyType.valueOf(value)
    }
}

@Database(
    entities = [Server::class, SshKey::class],
    version = 5, // Incremented version for keyType field
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ServerDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun keyDao(): KeyDao

    companion object {
        @Volatile
        private var INSTANCE: ServerDatabase? = null

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN ssh_key_id TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ssh_keys ADD COLUMN keyType TEXT NOT NULL DEFAULT 'ED25519'")
            }
        }

        fun getDatabase(context: Context): ServerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ServerDatabase::class.java,
                    "server_database"
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

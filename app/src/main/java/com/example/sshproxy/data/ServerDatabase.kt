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
    version = 8, // Incremented version for HTTP proxy auth fields removal
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

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN http_proxy_port INTEGER NOT NULL DEFAULT 8080")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN http_proxy_auth_enabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE servers ADD COLUMN http_proxy_username TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE servers ADD COLUMN http_proxy_password TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create a new table with the desired schema
                db.execSQL("""
                    CREATE TABLE servers_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        host TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        username TEXT NOT NULL,
                        http_proxy_port INTEGER NOT NULL,
                        ssh_key_id TEXT,
                        is_favorite INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        last_used INTEGER
                    )
                """)

                // Copy the data from the old table to the new table
                db.execSQL("""
                    INSERT INTO servers_new (id, name, host, port, username, http_proxy_port, ssh_key_id, is_favorite, created_at, last_used)
                    SELECT id, name, host, port, username, http_proxy_port, ssh_key_id, is_favorite, created_at, last_used FROM servers
                """)

                // Remove the old table
                db.execSQL("DROP TABLE servers")

                // Rename the new table to the original table name
                db.execSQL("ALTER TABLE servers_new RENAME TO servers")
            }
        }

        fun getDatabase(context: Context): ServerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ServerDatabase::class.java,
                    "server_database"
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
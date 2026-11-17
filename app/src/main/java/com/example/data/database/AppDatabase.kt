package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.ConnectionDao
import com.example.data.dao.DeviceIdentityDao
import com.example.data.dao.EmergencyBroadcastDao
import com.example.data.dao.EventLogDao
import com.example.data.dao.FileTransferDao
import com.example.data.dao.GroupDao
import com.example.data.dao.MessageDao
import com.example.data.dao.PeerDao
import com.example.data.dao.PendingPacketDao
import com.example.data.dao.RouteDao
import com.example.data.dao.SettingsDao
import com.example.data.dao.UserSettingsDao
import com.example.data.entities.ConnectionEntity
import com.example.data.entities.DeviceIdentityEntity
import com.example.data.entities.EmergencyBroadcastEntity
import com.example.data.entities.FileChunkEntity
import com.example.data.entities.FileTransferEntity
import com.example.data.entities.GroupEntity
import com.example.data.entities.GroupMemberEntity
import com.example.data.entities.GroupKeyUpdateEntity
import com.example.data.entities.MessageEntity
import com.example.data.entities.NetworkEventEntity
import com.example.data.entities.PeerEntity
import com.example.data.entities.PendingPacketEntity
import com.example.data.entities.RouteEntity
import com.example.data.entities.SecurityEventEntity
import com.example.data.entities.SettingsEntity
import com.example.data.entities.UserSettings

@Database(
    entities = [
        DeviceIdentityEntity::class,
        PeerEntity::class,
        ConnectionEntity::class,
        MessageEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        GroupKeyUpdateEntity::class,
        EmergencyBroadcastEntity::class,
        RouteEntity::class,
        PendingPacketEntity::class,
        FileTransferEntity::class,
        FileChunkEntity::class,
        SecurityEventEntity::class,
        NetworkEventEntity::class,
        SettingsEntity::class,
        UserSettings::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceIdentityDao(): DeviceIdentityDao
    abstract fun peerDao(): PeerDao
    abstract fun connectionDao(): ConnectionDao
    abstract fun messageDao(): MessageDao
    abstract fun groupDao(): GroupDao
    abstract fun emergencyBroadcastDao(): EmergencyBroadcastDao
    abstract fun routeDao(): RouteDao
    abstract fun pendingPacketDao(): PendingPacketDao
    abstract fun fileTransferDao(): FileTransferDao
    abstract fun eventLogDao(): EventLogDao
    abstract fun settingsDao(): SettingsDao
    abstract fun userSettingsDao(): UserSettingsDao

    companion object {
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE settings ADD COLUMN bluetoothClassicEnabled INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `group_key_updates` (
                        `packetId` TEXT NOT NULL,
                        `groupId` TEXT NOT NULL,
                        `epoch` INTEGER NOT NULL,
                        `peerId` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `acknowledgedAt` INTEGER,
                        PRIMARY KEY(`packetId`)
                    )""".trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_group_key_updates_groupId ON group_key_updates(groupId)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE group_members ADD COLUMN roleUpdatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE group_key_updates ADD COLUMN revokedPeerId TEXT")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "meshlink_local.db"
                ).addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration(dropAllTables = true).build().also {
                    INSTANCE = it
                }
            }
        }
    }
}

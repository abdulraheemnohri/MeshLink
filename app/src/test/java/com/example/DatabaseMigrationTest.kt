package com.example

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseMigrationTest {

    @Test
    fun migration3To8_preservesExistingRowsAndAddsEveryExpectedColumn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE settings (id INTEGER NOT NULL PRIMARY KEY)"
                        )
                        db.execSQL(
                            """CREATE TABLE group_members (
                                groupId TEXT NOT NULL,
                                peerId TEXT NOT NULL,
                                peerAlias TEXT NOT NULL,
                                role TEXT NOT NULL,
                                PRIMARY KEY(groupId, peerId)
                            )""".trimIndent()
                        )
                        db.execSQL("INSERT INTO settings(id) VALUES (7)")
                        db.execSQL(
                            "INSERT INTO group_members(groupId, peerId, peerAlias, role) " +
                                "VALUES ('g1', 'p1', 'Peer One', 'MEMBER')"
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        error("The test creates a version 3 database and applies migrations explicitly")
                    }
                })
                .build()
        )

        try {
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_3_4.migrate(db)
            AppDatabase.MIGRATION_4_5.migrate(db)
            AppDatabase.MIGRATION_5_6.migrate(db)
            AppDatabase.MIGRATION_6_7.migrate(db)
            AppDatabase.MIGRATION_7_8.migrate(db)
            AppDatabase.MIGRATION_8_9.migrate(db)

            assertColumns(
                db,
                "settings",
                setOf("id", "bluetoothClassicEnabled", "nearbyConnectionsEnabled")
            )
            assertColumns(
                db,
                "group_members",
                setOf("groupId", "peerId", "peerAlias", "role", "roleUpdatedAt")
            )
            assertColumns(
                db,
                "group_key_updates",
                setOf(
                    "packetId",
                    "groupId",
                    "epoch",
                    "peerId",
                    "status",
                    "updatedAt",
                    "acknowledgedAt",
                    "revokedPeerId"
                )
            )

            db.query("SELECT id FROM settings WHERE id = 7").use { cursor ->
                assertTrue("The pre-migration settings row must remain", cursor.moveToFirst())
                assertEquals(7, cursor.getInt(0))
            }
            db.query(
                "SELECT peerAlias, role, roleUpdatedAt FROM group_members WHERE groupId = 'g1' AND peerId = 'p1'"
            ).use { cursor ->
                assertTrue("The pre-migration group member must remain", cursor.moveToFirst())
                assertEquals("Peer One", cursor.getString(0))
                assertEquals("MEMBER", cursor.getString(1))
                assertEquals(0L, cursor.getLong(2))
            }

            db.execSQL(
                """INSERT INTO group_key_updates(
                    packetId, groupId, epoch, peerId, status, updatedAt, revokedPeerId
                ) VALUES ('packet-1', 'g1', 2, 'p1', 'QUEUED', 100, 'p0')""".trimIndent()
            )
            db.query(
                "SELECT revokedPeerId FROM group_key_updates WHERE packetId = 'packet-1'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("p0", cursor.getString(0))
            }

            assertColumns(
                db,
                "group_role_updates",
                setOf(
                    "packetId", "groupId", "epoch", "peerId", "targetPeerId",
                    "role", "issuedAt", "status", "updatedAt", "acknowledgedAt"
                )
            )
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_group_key_updates_groupId'"
            ).use { cursor ->
                assertTrue("The group key update lookup index must exist", cursor.moveToFirst())
            }
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_group_role_updates_peerId'"
            ).use { cursor ->
                assertTrue("The group role recipient lookup index must exist", cursor.moveToFirst())
            }
        } finally {
            helper.close()
        }
    }

    private fun assertColumns(
        db: SupportSQLiteDatabase,
        table: String,
        expected: Set<String>
    ) {
        val actual = mutableSetOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) actual += cursor.getString(nameColumn)
        }
        assertTrue(
            "Expected columns $expected in $table, found $actual",
            actual.containsAll(expected)
        )
    }
}

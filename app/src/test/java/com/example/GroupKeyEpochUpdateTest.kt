package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.crypto.MeshCryptoEngine
import com.example.data.database.AppDatabase
import com.example.data.entities.GroupEntity
import com.example.data.entities.GroupMemberEntity
import com.example.data.repository.MeshRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupKeyEpochUpdateTest {
    private lateinit var db: AppDatabase
    private lateinit var crypto: MeshCryptoEngine
    private lateinit var repository: MeshRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        crypto = MeshCryptoEngine(context)
        repository = MeshRepository(db, crypto)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun onlyGroupAdminCanApplyNewEpochAndRevocation() = runBlocking {
        val oldKey = crypto.generateGroupKeyB64()
        val newKey = crypto.generateGroupKeyB64()
        db.groupDao().upsertGroup(
            GroupEntity(
                groupId = "group_epoch_test",
                name = "Epoch test",
                groupSymmetricKeyB64 = oldKey,
                keyEpoch = 4,
                createdBy = "admin_peer"
            )
        )
        db.groupDao().upsertMember(
            GroupMemberEntity("group_epoch_test", "admin_peer", "Admin", "ADMIN")
        )
        db.groupDao().upsertMember(
            GroupMemberEntity("group_epoch_test", "local_peer", "Local", "MEMBER")
        )
        db.groupDao().upsertMember(
            GroupMemberEntity("group_epoch_test", "revoked_peer", "Revoked", "MEMBER")
        )

        val unauthorized = repository.applyGroupKeyUpdate(
            "group_epoch_test", 5, newKey, "local_peer", null
        )
        assertFalse(unauthorized)

        val applied = repository.applyGroupKeyUpdate(
            "group_epoch_test", 5, newKey, "admin_peer", "revoked_peer"
        )
        assertTrue(applied)
        val stored = db.groupDao().getGroupById("group_epoch_test")!!
        assertEquals(5, stored.keyEpoch)
        assertEquals(newKey, stored.groupSymmetricKeyB64)
        assertNull(db.groupDao().getMember("group_epoch_test", "revoked_peer"))

        val replay = repository.applyGroupKeyUpdate(
            "group_epoch_test", 5, oldKey, "admin_peer", null
        )
        assertFalse(replay)
        assertEquals(newKey, db.groupDao().getGroupById("group_epoch_test")!!.groupSymmetricKeyB64)
    }
}

package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.entities.GroupKeyUpdateEntity
import com.example.data.entities.GroupRoleUpdateEntity
import com.example.data.entities.GroupEntity
import com.example.data.entities.GroupMemberEntity
import com.example.data.entities.DeviceIdentity
import com.example.data.entities.Peers
import com.example.data.entities.PendingPacketEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppDatabaseTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun groupKeyUpdateDao_persistsAcknowledgementsAndRoleTimestamps() = runBlocking {
        val dao = db.groupDao()
        dao.upsertGroup(
            GroupEntity(
                groupId = "group_test",
                name = "Test group",
                groupSymmetricKeyB64 = "test-key",
                createdBy = "admin"
            )
        )
        dao.upsertMember(
            GroupMemberEntity(
                groupId = "group_test",
                peerId = "admin",
                peerAlias = "Admin",
                role = "ADMIN"
            )
        )
        dao.upsertKeyUpdate(
            GroupKeyUpdateEntity(
                packetId = "packet_epoch_2",
                groupId = "group_test",
                epoch = 2,
                peerId = "member"
            )
        )

        assertEquals(1, dao.countUnacknowledgedKeyUpdates("group_test"))
        val acknowledgedAt = System.currentTimeMillis()
        dao.updateKeyUpdateStatus("packet_epoch_2", "ACKED", acknowledgedAt, acknowledgedAt)
        dao.updateKeyUpdateStatus("packet_epoch_2", "SENT", acknowledgedAt + 1, null)
        assertEquals(0, dao.countUnacknowledgedKeyUpdates("group_test"))
        assertEquals("ACKED", dao.getKeyUpdateByPacketId("packet_epoch_2")?.status)

        dao.updateMemberRole("group_test", "admin", "ADMIN", acknowledgedAt)
        assertEquals(acknowledgedAt, dao.getMember("group_test", "admin")?.roleUpdatedAt)
        assertEquals(1, dao.countAdmins("group_test"))
    }

    @Test
    fun groupKeyRecovery_tracksRevocationAndSupersedesOldEpochs() = runBlocking {
        val dao = db.groupDao()
        dao.upsertKeyUpdate(
            GroupKeyUpdateEntity(
                packetId = "failed_epoch_4",
                groupId = "group_recovery",
                epoch = 4,
                peerId = "member_1",
                status = "FAILED",
                revokedPeerId = "removed_member"
            )
        )
        assertEquals(1, dao.countUnacknowledgedKeyUpdates("group_recovery"))
        assertEquals("removed_member", dao.getKeyUpdateByPacketId("failed_epoch_4")?.revokedPeerId)
        assertEquals(1, dao.getUnresolvedKeyUpdates("group_recovery").size)

        dao.supersedeOlderKeyUpdates("group_recovery", 5, 500L)
        assertEquals(0, dao.countUnacknowledgedKeyUpdates("group_recovery"))
        assertEquals("SUPERSEDED", dao.getKeyUpdateByPacketId("failed_epoch_4")?.status)

        dao.upsertKeyUpdate(
            GroupKeyUpdateEntity(
                packetId = "retry_epoch_5",
                groupId = "group_recovery",
                epoch = 5,
                peerId = "member_1",
                status = "QUEUED",
                revokedPeerId = "removed_member"
            )
        )
        assertEquals(1, dao.countUnacknowledgedKeyUpdates("group_recovery"))
        assertEquals("removed_member", dao.getUnresolvedKeyUpdates("group_recovery").single().revokedPeerId)
    }

    @Test
    fun groupRoleUpdateDao_persistsBatchRecipientsAndIndependentAcknowledgements() = runBlocking {
        val dao = db.groupDao()
        dao.upsertRoleUpdates(
            listOf(
                GroupRoleUpdateEntity(
                    packetId = "role_packet_1",
                    groupId = "role_group",
                    epoch = 3,
                    peerId = "recipient_1",
                    targetPeerId = "member_2",
                    role = "ADMIN",
                    issuedAt = 1234L,
                    status = "QUEUED"
                ),
                GroupRoleUpdateEntity(
                    packetId = "role_packet_2",
                    groupId = "role_group",
                    epoch = 3,
                    peerId = "recipient_2",
                    targetPeerId = "member_2",
                    role = "ADMIN",
                    issuedAt = 1234L,
                    status = "PENDING"
                )
            )
        )

        assertEquals(2, dao.countUnacknowledgedRoleUpdates("role_group"))
        assertEquals("recipient_1", dao.getUnresolvedRoleUpdatesForPeer("recipient_1").single().peerId)
        assertEquals("recipient_2", dao.getUnresolvedRoleUpdatesForPeer("recipient_2").single().peerId)
        assertEquals("ADMIN", dao.getRoleUpdateByPacketId("role_packet_1")?.role)

        dao.updateRoleUpdateStatus("role_packet_1", "ACKED", 5678L, 5678L)
        // A late sender-side SENT/FAILED update must never overwrite a fast ACK.
        dao.updateRoleUpdateStatus("role_packet_1", "SENT", 6000L, null)
        assertEquals("ACKED", dao.getRoleUpdateByPacketId("role_packet_1")?.status)
        assertEquals(1, dao.countUnacknowledgedRoleUpdates("role_group"))
        assertTrue(dao.getUnresolvedRoleUpdatesForPeer("recipient_1").isEmpty())
        assertEquals(5678L, dao.getRoleUpdateByPacketId("role_packet_1")?.acknowledgedAt)

        dao.updateRoleUpdateStatus("role_packet_2", "ACKED", 6789L, 6789L)
        assertEquals(0, dao.countUnacknowledgedRoleUpdates("role_group"))
        assertTrue(dao.getUnresolvedRoleUpdates("role_group").isEmpty())
        assertEquals(6789L, dao.getRoleUpdateByPacketId("role_packet_2")?.acknowledgedAt)
    }

    @Test
    fun pendingPacketDao_persistsAttemptCountAndLastAttemptTimestamp() = runBlocking {
        val dao = db.pendingPacketDao()
        dao.upsert(
            PendingPacketEntity(
                messageId = "queued-retry",
                destinationId = "peer-destination",
                packetType = "MESSAGE",
                routingStrategy = "STORE_AND_FORWARD",
                rawPacketJson = "{}",
                expiresAt = System.currentTimeMillis() + 60_000L
            )
        )

        assertEquals(0, dao.getById("queued-retry")?.attemptCount)
        assertEquals(1, dao.recordAttempt("queued-retry", 100_000L))
        assertEquals(1, dao.recordAttempt("queued-retry", 130_000L))

        val recovered = dao.getById("queued-retry")
        assertEquals(2, recovered?.attemptCount)
        assertEquals(130_000L, recovered?.lastAttemptAt)
    }

    @Test
    fun settingsDao_persistsNearbyConnectionsOptIn() = runBlocking {
        val dao = db.settingsDao()
        dao.upsert(com.example.data.entities.SettingsEntity(nearbyConnectionsEnabled = true))
        assertTrue(dao.getSettingsOnce()!!.nearbyConnectionsEnabled)
    }

    @Test
    fun deviceIdentityDao_persistsAndUpdatesLocalNode() = runBlocking {
        val dao = db.deviceIdentityDao()
        assertNull(dao.getIdentityOnce())

        val identity = DeviceIdentity(
            deviceId = "node_local_01",
            alias = "RelayAlpha",
            signingPublicKey = "sig_pub_b64",
            agreementPublicKey = "kx_pub_b64",
            fingerprint = "AA:BB:CC:DD"
        )
        dao.upsert(identity)

        val fetched = dao.getIdentityOnce()
        assertNotNull(fetched)
        assertEquals("RelayAlpha", fetched!!.alias)

        dao.update(fetched.copy(alias = "RelayAlphaUpdated"))
        val observed = dao.observeIdentity().first()
        assertEquals("RelayAlphaUpdated", observed?.alias)

        dao.clearAll()
        assertNull(dao.getIdentityOnce())
    }

    @Test
    fun peerDao_persistsQueriesAndDeletesPeers() = runBlocking {
        val peerDao = db.peerDao()
        val p1 = Peers(
            deviceId = "peer_01",
            alias = "NodeBravo",
            signingPublicKey = "sig1",
            agreementPublicKey = "kx1",
            fingerprint = "FP1",
            isConnected = true,
            activeTransport = "BLE"
        )
        val p2 = Peers(
            deviceId = "peer_02",
            alias = "NodeCharlie",
            signingPublicKey = "sig2",
            agreementPublicKey = "kx2",
            fingerprint = "FP2",
            isConnected = false,
            activeTransport = "NONE"
        )
        peerDao.upsertAll(listOf(p1, p2))

        val all = peerDao.observeAllPeers().first()
        assertEquals(2, all.size)
        assertEquals("peer_01", all.first().deviceId)

        val connected = peerDao.observeConnectedPeers().first()
        assertEquals(1, connected.size)
        assertEquals("NodeBravo", connected.first().alias)

        peerDao.setVerified("peer_02", true)
        assertTrue(peerDao.getPeerById("peer_02")?.verifiedByQr == true)

        peerDao.updateConnectionState("peer_02", true, "LOCAL_LAN", 123456L)
        assertEquals(2, peerDao.getConnectedPeersOnce().size)

        peerDao.deleteById("peer_01")
        assertNull(peerDao.getPeerById("peer_01"))
    }

    @Test
    fun userSettingsDao_persistsBiometricPreferences() = runBlocking {
        val userSettingsDao = db.userSettingsDao()
        assertNull(userSettingsDao.getUserSettingsOnce())

        val settings = com.example.data.entities.UserSettings(
            biometricLockEnabled = true,
            requireAuthOnLaunch = true,
            biometricPromptTitle = "Vault Test"
        )
        userSettingsDao.upsert(settings)

        val fetched = userSettingsDao.getUserSettingsOnce()
        assertNotNull(fetched)
        assertTrue(fetched!!.biometricLockEnabled)
        assertEquals("Vault Test", fetched.biometricPromptTitle)

        userSettingsDao.setBiometricLockEnabled(false)
        val updated = userSettingsDao.getUserSettingsOnce()
        org.junit.Assert.assertFalse(updated!!.biometricLockEnabled)
    }
}

package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.crypto.MeshCryptoEngine
import com.example.core.crypto.PeerIdentityPayload
import com.example.data.database.AppDatabase
import com.example.data.repository.MeshRepository
import com.example.data.repository.PeerIdentityKeyChangedException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PeerIdentityPinningTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: MeshRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MeshRepository(db, MeshCryptoEngine(context))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun qrImportStartsUntrustedAndChangedKeysCannotReplacePinnedIdentity() = runBlocking {
        val original = PeerIdentityPayload(
            deviceId = "peer_pinned_01",
            alias = "Trusted candidate",
            signingPublicKeyB64 = "signing-key-a",
            agreementPublicKeyB64 = "agreement-key-a",
            fingerprint = "FP-A"
        )

        val imported = repository.importVerifiedPeer(original)
        assertFalse(imported.verifiedByQr)

        db.peerDao().setVerified(original.deviceId, true)
        val sameIdentity = repository.importVerifiedPeer(original)
        assertTrue(sameIdentity.verifiedByQr)

        val changed = original.copy(
            signingPublicKeyB64 = "attacker-signing-key",
            agreementPublicKeyB64 = "attacker-agreement-key",
            fingerprint = "FP-B"
        )
        assertThrows(PeerIdentityKeyChangedException::class.java) {
            runBlocking { repository.importVerifiedPeer(changed) }
        }

        val stored = db.peerDao().getPeerById(original.deviceId)!!
        assertEquals("FP-A", stored.fingerprint)
        assertEquals("signing-key-a", stored.signingPublicKey)
        assertTrue(stored.verifiedByQr)
    }
}

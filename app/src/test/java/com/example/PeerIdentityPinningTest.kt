package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.util.Base64
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
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PeerIdentityPinningTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: MeshRepository
    private lateinit var crypto: MeshCryptoEngine

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
    fun qrImportStartsUntrustedAndChangedKeysCannotReplacePinnedIdentity() = runBlocking {
        val keyGenerator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val firstSigning = Base64.encodeToString(keyGenerator.generateKeyPair().public.encoded, Base64.NO_WRAP)
        val firstAgreement = Base64.encodeToString(keyGenerator.generateKeyPair().public.encoded, Base64.NO_WRAP)
        val firstFingerprint = crypto.computeFingerprint(firstSigning, firstAgreement)
        val deviceId = firstFingerprint.replace(":", "").take(16).lowercase()
        val original = PeerIdentityPayload(
            deviceId = deviceId,
            alias = "Trusted candidate",
            signingPublicKeyB64 = firstSigning,
            agreementPublicKeyB64 = firstAgreement,
            fingerprint = firstFingerprint
        )

        val imported = repository.importVerifiedPeer(original)
        assertFalse(imported.verifiedByQr)

        db.peerDao().setVerified(original.deviceId, true)
        db.peerDao().setBlocked(original.deviceId, true)
        val sameIdentity = repository.importVerifiedPeer(original)
        assertTrue(sameIdentity.verifiedByQr)
        assertTrue(sameIdentity.blocked)

        val changedSigning = Base64.encodeToString(keyGenerator.generateKeyPair().public.encoded, Base64.NO_WRAP)
        val changedAgreement = Base64.encodeToString(keyGenerator.generateKeyPair().public.encoded, Base64.NO_WRAP)
        val changed = original.copy(
            signingPublicKeyB64 = changedSigning,
            agreementPublicKeyB64 = changedAgreement,
            fingerprint = crypto.computeFingerprint(changedSigning, changedAgreement)
        )
        assertThrows(PeerIdentityKeyChangedException::class.java) {
            runBlocking { repository.importVerifiedPeer(changed) }
        }

        val stored = db.peerDao().getPeerById(original.deviceId)!!
        assertEquals(firstFingerprint, stored.fingerprint)
        assertEquals(firstSigning, stored.signingPublicKey)
        assertTrue(stored.verifiedByQr)
        assertTrue(stored.blocked)
    }
}

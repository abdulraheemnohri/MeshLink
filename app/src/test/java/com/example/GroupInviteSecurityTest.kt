package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.crypto.MeshCryptoEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupInviteSecurityTest {
    @Suppress("DEPRECATION")
    @Test
    fun publicGroupInviteUriNeverContainsGroupKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crypto = MeshCryptoEngine(context)
        val secret = "THIS_IS_A_TEST_GROUP_SECRET_32_BYTES"

        val uri = crypto.encodeGroupInviteUri(
            groupId = "group_test_01",
            name = "Test group",
            description = "Test only",
            category = "COMMUNITY",
            groupKeyB64 = secret,
            epoch = 3,
            createdBy = "node_admin"
        )

        assertFalse(uri.contains(secret))
        assertTrue(uri.startsWith("meshlink://group-invite-disabled"))
    }

    @Suppress("DEPRECATION")
    @Test
    fun legacyGroupInviteWithEmbeddedKeyIsRejected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crypto = MeshCryptoEngine(context)
        val legacy = "meshlink://group?v=1&id=group_test&name=Test&key=legacy-secret&ep=1&by=admin"

        assertNull(crypto.decodeGroupInviteUri(legacy))
    }
}

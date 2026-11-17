package com.example

import com.example.data.profile.LocalUserProfile
import com.example.data.profile.LocalUserProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LocalUserProfileStoreTest {
    @Test
    fun savesAndReloadsLocalProfile() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("meshlink_local_profile", 0).edit().clear().commit()
        val store = LocalUserProfileStore(context)
        val saved = store.save(
            LocalUserProfile(
                displayName = "  Mesh User  ",
                about = "Offline-first",
                status = "Available",
                avatarEmoji = "🛰️",
                shareDisplayName = false,
                allowContactRequests = true,
                showOnlineStatus = true
            )
        )

        val loaded = LocalUserProfileStore(context).load()
        assertEquals("Mesh User", loaded.displayName)
        assertEquals("Offline-first", loaded.about)
        assertEquals("🛰️", loaded.avatarEmoji)
        assertTrue(!loaded.shareDisplayName)
        assertTrue(loaded.allowContactRequests)
        assertTrue(loaded.showOnlineStatus)
        assertTrue(saved.updatedAt > 0L)
    }

    @Test
    fun boundsUserSuppliedProfileFields() {
        val context = RuntimeEnvironment.getApplication()
        val saved = LocalUserProfileStore(context).save(
            LocalUserProfile(
                displayName = "n".repeat(80),
                about = "a".repeat(200),
                status = "s".repeat(50),
                avatarEmoji = "🙂".repeat(10)
            )
        )
        assertEquals(48, saved.displayName.length)
        assertEquals(160, saved.about.length)
        assertEquals(32, saved.status.length)
        assertTrue(saved.avatarEmoji.length <= 16)
    }
}

package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.routing.PacketReplayStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PacketReplayStoreTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun persistsPacketIdsAcrossStoreInstances() {
        val first = PacketReplayStore(context)
        assertTrue(first.rememberIfNew("message-1", expiresAt = 50_000L, now = 10_000L))

        val afterRestart = PacketReplayStore(context)
        assertTrue(afterRestart.contains("message-1", now = 11_000L))
        assertFalse(afterRestart.rememberIfNew("message-1", expiresAt = 50_000L, now = 11_000L))
    }

    @Test
    fun expiresOldEntriesAndAcceptsNewPackets() {
        val store = PacketReplayStore(context)
        assertTrue(store.rememberIfNew("old-message", expiresAt = 1_500L, now = 1_000L))
        assertFalse(store.contains("old-message", now = 1_500L))
        assertTrue(store.rememberIfNew("new-message", expiresAt = 5_000L, now = 1_500L))
    }

    @Test
    fun rejectsInvalidIdsAndExpiredPackets() {
        val store = PacketReplayStore(context)
        assertFalse(store.rememberIfNew("", expiresAt = 5_000L, now = 1_000L))
        assertFalse(store.rememberIfNew("expired", expiresAt = 999L, now = 1_000L))
        assertFalse(store.contains("x".repeat(257), now = 1_000L))
    }

    @Test
    fun enforcesConfiguredCapacity() {
        val store = PacketReplayStore(context, maxEntries = 2)
        assertTrue(store.rememberIfNew("one", expiresAt = 3_000L, now = 1_000L))
        assertTrue(store.rememberIfNew("two", expiresAt = 4_000L, now = 1_000L))
        assertTrue(store.rememberIfNew("three", expiresAt = 5_000L, now = 1_000L))
        assertFalse(store.contains("one", now = 1_000L))
        assertTrue(store.contains("three", now = 1_000L))
    }
}

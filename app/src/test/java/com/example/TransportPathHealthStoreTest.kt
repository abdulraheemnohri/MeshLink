package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.protocol.TransportType
import com.example.transport.TransportPathHealthStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransportPathHealthStoreTest {
    private fun store() = TransportPathHealthStore(ApplicationProvider.getApplicationContext<Context>())
    private val peer = "peer-${UUID.randomUUID()}"

    @Test
    fun failedPathEntersExponentialCooldown() {
        val health = store()
        val first = health.recordFailure(peer, TransportType.BLE, now = 10_000L)
        assertEquals(11_000L, first)
        assertFalse(health.shouldAttempt(peer, TransportType.BLE, now = 10_999L))
        assertTrue(health.shouldAttempt(peer, TransportType.BLE, now = first))
        val second = health.recordFailure(peer, TransportType.BLE, now = first)
        assertEquals(13_000L, second)
        assertEquals(2, health.failureCount(peer, TransportType.BLE))
    }

    @Test
    fun successClearsFailureAndCooldown() {
        val health = store()
        health.recordFailure(peer, TransportType.LOCAL_LAN, now = 1_000L)
        health.recordSuccess(peer, TransportType.LOCAL_LAN, now = 2_000L)
        assertEquals(0, health.failureCount(peer, TransportType.LOCAL_LAN))
        assertTrue(health.shouldAttempt(peer, TransportType.LOCAL_LAN, now = 2_000L))
        assertEquals(2_000L, health.lastSuccessAt(peer, TransportType.LOCAL_LAN))
    }

    @Test
    fun healthIsScopedToPeerAndTransport() {
        val health = store()
        health.recordFailure(peer, TransportType.BLE, now = 5_000L)
        assertTrue(health.shouldAttempt(peer, TransportType.LOCAL_LAN, now = 5_000L))
        assertTrue(health.shouldAttempt("another-peer", TransportType.BLE, now = 5_000L))
    }
}

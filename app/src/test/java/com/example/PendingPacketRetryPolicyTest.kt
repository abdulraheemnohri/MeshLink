package com.example

import com.example.routing.PendingPacketRetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPacketRetryPolicyTest {
    @Test
    fun firstAttemptIsImmediate() {
        assertEquals(0L, PendingPacketRetryPolicy.retryDelayMs(0))
        assertTrue(PendingPacketRetryPolicy.shouldAttempt(0, 0L, 1_000L))
    }

    @Test
    fun retryDelayDoublesAndCapsAtFifteenMinutes() {
        assertEquals(15_000L, PendingPacketRetryPolicy.retryDelayMs(1))
        assertEquals(30_000L, PendingPacketRetryPolicy.retryDelayMs(2))
        assertEquals(60_000L, PendingPacketRetryPolicy.retryDelayMs(3))
        assertEquals(15L * 60L * 1_000L, PendingPacketRetryPolicy.retryDelayMs(20))
        assertEquals(15L * 60L * 1_000L, PendingPacketRetryPolicy.retryDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun packetIsNotRetriedBeforeDelayButIsRetriedAtBoundary() {
        val lastAttempt = 100_000L
        assertFalse(PendingPacketRetryPolicy.shouldAttempt(2, lastAttempt, lastAttempt + 29_999L))
        assertTrue(PendingPacketRetryPolicy.shouldAttempt(2, lastAttempt, lastAttempt + 30_000L))
    }

    @Test
    fun clockMovingBackwardsDoesNotTriggerRetry() {
        assertFalse(PendingPacketRetryPolicy.shouldAttempt(1, 10_000L, 9_999L))
    }
}

package com.example

import com.example.core.protocol.MeshPacket
import com.example.core.protocol.PacketType
import com.example.transport.HandshakeReplayGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeReplayGuardTest {
    private fun packet(
        id: String = "hello-1",
        createdAt: Long = 100_000L,
        expiresAt: Long = 160_000L,
        type: PacketType = PacketType.HELLO,
        version: Int = MeshPacket.PROTOCOL_VERSION
    ) = MeshPacket(
        protocolVersion = version,
        messageId = id,
        senderId = "peer-a",
        destinationId = MeshPacket.BROADCAST_ID,
        packetType = type,
        createdAt = createdAt,
        expiresAt = expiresAt,
        encryptedPayload = "{}"
    )

    @Test fun acceptsFreshHelloOnceOnly() {
        val guard = HandshakeReplayGuard()
        assertTrue(guard.accept(packet(), now = 100_500L))
        assertFalse(guard.accept(packet(), now = 100_600L))
    }

    @Test fun rejectsExpiredAndStaleHello() {
        val guard = HandshakeReplayGuard()
        assertFalse(guard.accept(packet(expiresAt = 99_000L), now = 100_000L))
        assertFalse(guard.accept(packet(createdAt = 1L), now = 100_000L))
    }

    @Test fun rejectsHelloFromFutureOutsideClockSkew() {
        val guard = HandshakeReplayGuard()
        assertFalse(guard.accept(packet(createdAt = 200_000L, expiresAt = 260_000L), now = 100_000L))
    }

    @Test fun rejectsNonHelloPackets() {
        val guard = HandshakeReplayGuard()
        assertFalse(guard.accept(packet(type = PacketType.MESSAGE), now = 100_000L))
    }

    @Test fun rejectsUnsupportedProtocolVersions() {
        val guard = HandshakeReplayGuard()
        assertFalse(guard.accept(packet(version = MeshPacket.PROTOCOL_VERSION + 1), now = 100_000L))
    }

    @Test fun permitsDistinctFreshHelloIds() {
        val guard = HandshakeReplayGuard()
        assertTrue(guard.accept(packet(id = "hello-a"), now = 100_000L))
        assertTrue(guard.accept(packet(id = "hello-b"), now = 100_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroCapacity() {
        HandshakeReplayGuard(maxEntries = 0)
    }
}

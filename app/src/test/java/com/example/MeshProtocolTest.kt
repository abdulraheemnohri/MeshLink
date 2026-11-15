package com.example

import com.example.core.protocol.MeshPacket
import com.example.core.protocol.MeshPacketCodec
import com.example.core.protocol.PacketType
import com.example.core.protocol.RoutingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MeshProtocolTest {
    @Test
    fun packetCodec_roundTripAndIntegrityHash_validates() {
        val original = MeshPacket(
            senderId = "node_a1b2c3",
            destinationId = "node_z9y8x7",
            packetType = PacketType.MESSAGE,
            hopLimit = 6,
            routingStrategy = RoutingStrategy.SPRAY_AND_WAIT,
            encryptedPayload = "ciphertext_b64_payload",
            visitedPeers = listOf("node_a1b2c3", "node_relay1"),
            sprayCopies = 4,
            priority = MeshPacket.PRIORITY_HIGH
        )
        val hash = MeshPacketCodec.computeIntegrityHash(original)
        val signed = original.copy(integrityHash = hash, signature = "sig_demo")

        val wire = MeshPacketCodec.encode(signed)
        val decoded = MeshPacketCodec.decode(wire)
        assertNotNull(decoded)
        assertEquals(signed.messageId, decoded!!.messageId)
        assertEquals(signed.senderId, decoded.senderId)
        assertEquals(signed.destinationId, decoded.destinationId)
        assertEquals(PacketType.MESSAGE, decoded.packetType)
        assertEquals(RoutingStrategy.SPRAY_AND_WAIT, decoded.routingStrategy)
        assertEquals(hash, MeshPacketCodec.computeIntegrityHash(decoded))
        assertEquals(listOf("node_a1b2c3", "node_relay1"), decoded.visitedPeers)
    }

    @Test
    fun packetIntegrity_detectsTampering() {
        val original = MeshPacket(
            senderId = "node_a",
            destinationId = "node_c",
            packetType = PacketType.MESSAGE,
            encryptedPayload = "encrypted_abc"
        )
        val expectedHash = MeshPacketCodec.computeIntegrityHash(original)
        val tampered = original.copy(encryptedPayload = "encrypted_tampered")
        assertNotEquals(expectedHash, MeshPacketCodec.computeIntegrityHash(tampered))
    }

    @Test
    fun packetExpiration_respectsTtlWindow() {
        val now = 1_000_000L
        val active = MeshPacket(
            senderId = "a",
            destinationId = "b",
            packetType = PacketType.HELLO,
            createdAt = now,
            expiresAt = now + 10_000L,
            encryptedPayload = "{}"
        )
        assertFalse(active.isExpired(now + 5_000L))
        assertTrue(active.isExpired(now + 10_000L))
        assertTrue(active.isExpired(now + 15_000L))
    }

    @Test
    fun packetCodec_preservesBridgeSourceClusterAndSpecialPacketTypes() {
        listOf(
            PacketType.GROUP_INVITE,
            PacketType.EMERGENCY_BEACON,
            PacketType.BRIDGE_SYNC
        ).forEach { type ->
            val pkt = MeshPacket(
                senderId = "node_bridge",
                destinationId = MeshPacket.BROADCAST_ID,
                packetType = type,
                routingStrategy = RoutingStrategy.EPIDEMIC,
                priority = MeshPacket.PRIORITY_EMERGENCY,
                encryptedPayload = "{\"ok\":true}",
                sourceCluster = "BLE->LOCAL_LAN"
            )
            val hash = MeshPacketCodec.computeIntegrityHash(pkt)
            val wire = MeshPacketCodec.encode(pkt.copy(integrityHash = hash))
            val decoded = MeshPacketCodec.decode(wire)
            assertNotNull(decoded)
            assertEquals(type, decoded!!.packetType)
            assertEquals("BLE->LOCAL_LAN", decoded.sourceCluster)
            assertEquals(MeshPacket.PRIORITY_EMERGENCY, decoded.priority)
            assertEquals(hash, MeshPacketCodec.computeIntegrityHash(decoded))
        }
    }
}

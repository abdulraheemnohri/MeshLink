package com.example

import com.example.core.protocol.MeshPacket
import com.example.core.protocol.PacketType
import com.example.routing.MeshForwardingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshForwardingPolicyTest {

    private fun packet(
        hopLimit: Int = 4,
        hopCount: Int = 0,
        visited: List<String> = listOf("A"),
        destination: String = "C"
    ) = MeshPacket(
        senderId = "A",
        destinationId = destination,
        packetType = PacketType.MESSAGE,
        encryptedPayload = "ciphertext",
        hopLimit = hopLimit,
        hopCount = hopCount,
        visitedPeers = visited
    )

    @Test
    fun relayConsumesExactlyOneHopAndAddsLocalPeerOnce() {
        val original = packet(hopLimit = 4, hopCount = 2, visited = listOf("A"))
        val forwarded = MeshForwardingPolicy.forwardForRelay(original, "B")

        assertNotNull(forwarded)
        assertEquals(3, forwarded!!.hopLimit)
        assertEquals(3, forwarded.hopCount)
        assertEquals(listOf("A", "B"), forwarded.visitedPeers)
        assertEquals(4, original.hopLimit)
        assertEquals(listOf("A"), original.visitedPeers)
    }

    @Test
    fun relayStopsAtLastPermittedHop() {
        assertNull(MeshForwardingPolicy.forwardForRelay(packet(hopLimit = 1), "B"))
        assertNotNull(MeshForwardingPolicy.forwardForRelay(packet(hopLimit = 2), "B"))
    }

    @Test
    fun relayRejectsLocalDestinationAndAlreadyVisitedNode() {
        assertNull(MeshForwardingPolicy.forwardForRelay(packet(destination = "B"), "B"))
        assertNull(MeshForwardingPolicy.forwardForRelay(packet(visited = listOf("A", "B")), "B"))
    }

    @Test
    fun nextHopMustBeUnvisitedAndNotThePreviousSender() {
        val candidate = packet(visited = listOf("A", "B"))
        assertTrue(MeshForwardingPolicy.canForwardToNextHop(candidate, "C"))
        assertFalse(MeshForwardingPolicy.canForwardToNextHop(candidate, "A"))
        assertFalse(MeshForwardingPolicy.canForwardToNextHop(candidate, "B"))
        assertFalse(MeshForwardingPolicy.canForwardToNextHop(candidate, ""))
    }
}

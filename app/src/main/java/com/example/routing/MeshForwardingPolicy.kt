package com.example.routing

import com.example.core.protocol.MeshPacket

/**
 * Pure hop/loop policy shared by routing and tests.
 *
 * A relay may forward only once per path, must consume one hop, and must never
 * forward a packet whose destination is already this node.
 */
object MeshForwardingPolicy {
    fun forwardForRelay(packet: MeshPacket, localPeerId: String): MeshPacket? {
        if (localPeerId.isBlank()) return null
        if (packet.senderId == localPeerId || packet.destinationId == localPeerId) return null
        if (packet.hopLimit <= 1 || localPeerId in packet.visitedPeers) return null
        return packet.copy(
            hopLimit = packet.hopLimit - 1,
            hopCount = packet.hopCount + 1,
            visitedPeers = packet.visitedPeers + localPeerId
        )
    }

    fun canForwardToNextHop(packet: MeshPacket, nextHopPeerId: String): Boolean =
        nextHopPeerId.isNotBlank() &&
            nextHopPeerId != packet.senderId &&
            nextHopPeerId !in packet.visitedPeers
}

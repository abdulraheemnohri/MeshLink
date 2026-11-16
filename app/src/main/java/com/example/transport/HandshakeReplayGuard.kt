package com.example.transport

import com.example.core.protocol.MeshPacket
import com.example.core.protocol.PacketType
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded freshness and replay guard for signed HELLO frames.
 *
 * This is an in-memory, per-process defense. It does not replace signature
 * verification, persistent trust decisions, or a nonce-based session handshake.
 */
class HandshakeReplayGuard(
    private val maxClockSkewMs: Long = 60_000L,
    private val retentionMs: Long = 120_000L,
    private val maxEntries: Int = 2_048
) {
    private val seen = ConcurrentHashMap<String, Long>()

    init {
        require(maxClockSkewMs >= 0L) { "Clock skew must not be negative" }
        require(retentionMs > 0L) { "Replay retention must be positive" }
        require(maxEntries > 0) { "Replay cache must have at least one entry" }
    }

    @Synchronized
    fun accept(packet: MeshPacket, now: Long = System.currentTimeMillis()): Boolean {
        if (packet.packetType != PacketType.HELLO) return false
        if (packet.protocolVersion != MeshPacket.PROTOCOL_VERSION) return false
        if (packet.messageId.isBlank() || packet.createdAt <= 0L) return false
        if (packet.expiresAt <= now || packet.expiresAt <= packet.createdAt) return false
        if (packet.createdAt > now + maxClockSkewMs || now - packet.createdAt > maxClockSkewMs) return false

        val cutoff = now - retentionMs
        // Remove by key/value pair so a concurrent replacement cannot be deleted.
        seen.entries.forEach { entry ->
            if (entry.value < cutoff) seen.remove(entry.key, entry.value)
        }

        if (seen.containsKey(packet.messageId)) return false
        if (seen.size >= maxEntries) {
            seen.entries.minByOrNull { it.value }?.let { oldest ->
                seen.remove(oldest.key, oldest.value)
            }
        }
        seen[packet.messageId] = now
        return true
    }
}

package com.example.transport

import com.example.core.protocol.MeshPacket
import java.util.concurrent.ConcurrentHashMap

/** Bounded freshness and replay guard for signed handshake frames. */
class HandshakeReplayGuard(
    private val maxClockSkewMs: Long = 60_000L,
    private val retentionMs: Long = 120_000L,
    private val maxEntries: Int = 2_048
) {
    private val seen = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun accept(packet: MeshPacket, now: Long = System.currentTimeMillis()): Boolean {
        if (packet.messageId.isBlank() || packet.createdAt <= 0L) return false
        if (packet.expiresAt <= now || packet.expiresAt <= packet.createdAt) return false
        if (packet.createdAt > now + maxClockSkewMs || now - packet.createdAt > maxClockSkewMs) return false
        val cutoff = now - retentionMs
        seen.entries.removeAll { it.value < cutoff }
        if (seen.containsKey(packet.messageId)) return false
        if (seen.size >= maxEntries) {
            seen.entries.minByOrNull { it.value }?.key?.let(seen::remove)
        }
        seen[packet.messageId] = now
        return true
    }
}

package com.example.routing

import android.content.Context
import android.util.Base64

/**
 * Durable duplicate/replay suppression for authenticated mesh packets.
 *
 * Entries are persisted before packet delivery/forwarding. A failed disk commit
 * fails closed so the same packet is not processed without replay protection.
 */
class PacketReplayStore(
    context: Context,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    fun contains(messageId: String, now: Long = System.currentTimeMillis()): Boolean {
        if (!isValidId(messageId)) return false
        prune(now)
        return preferences.contains(key(messageId))
    }

    /**
     * Atomically records a packet ID if it has not already been seen.
     * Returns false for duplicates, expired packets, invalid IDs or persistence errors.
     */
    @Synchronized
    fun rememberIfNew(
        messageId: String,
        expiresAt: Long,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (!isValidId(messageId) || expiresAt <= now) return false
        prune(now)
        val entryKey = key(messageId)
        if (preferences.contains(entryKey)) return false
        val saved = preferences.edit().putLong(entryKey, expiresAt).commit()
        if (!saved) return false
        prune(now)
        return true
    }

    @Synchronized
    fun prune(now: Long = System.currentTimeMillis()) {
        val entries = preferences.all
            .filterKeys { it.startsWith(ENTRY_PREFIX) }
            .mapNotNull { (entryKey, value) ->
                val expiry = value as? Long ?: return@mapNotNull entryKey to Long.MIN_VALUE
                entryKey to expiry
            }
        val expired = entries.filter { it.second <= now }
        val excess = (entries.size - expired.size - maxEntries).coerceAtLeast(0)
        val oldest = entries.asSequence()
            .filter { it.second > now }
            .sortedBy { it.second }
            .take(excess)
            .toList()
        if (expired.isNotEmpty() || oldest.isNotEmpty()) {
            val editor = preferences.edit()
            (expired + oldest).forEach { (entryKey, _) -> editor.remove(entryKey) }
            editor.commit()
        }
    }

    private fun isValidId(messageId: String): Boolean =
        messageId.isNotBlank() && messageId.length <= MAX_ID_LENGTH

    private fun key(messageId: String): String {
        val encoded = Base64.encodeToString(
            messageId.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return ENTRY_PREFIX + encoded
    }

    companion object {
        private const val PREFERENCES = "meshlink_packet_replay_store"
        private const val ENTRY_PREFIX = "packet."
        private const val MAX_ID_LENGTH = 256
        const val DEFAULT_MAX_ENTRIES = 10_000
    }
}

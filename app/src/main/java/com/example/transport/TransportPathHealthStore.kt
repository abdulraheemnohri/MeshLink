package com.example.transport

import android.content.Context
import android.util.Base64
import com.example.core.protocol.TransportType

/**
 * Persists per-peer/per-radio delivery health so a temporarily broken path does not
 * repeatedly stall sends. Cooldowns are local hints, not a permanent blacklist.
 */
class TransportPathHealthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun failureCount(peerId: String, type: TransportType): Int =
        prefs.getInt(key(peerId, type, FAILURES), 0)

    fun shouldAttempt(
        peerId: String,
        type: TransportType,
        now: Long = System.currentTimeMillis()
    ): Boolean = prefs.getLong(key(peerId, type, COOLDOWN_UNTIL), 0L) <= now

    fun recordSuccess(peerId: String, type: TransportType, now: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putInt(key(peerId, type, FAILURES), 0)
            .putLong(key(peerId, type, COOLDOWN_UNTIL), 0L)
            .putLong(key(peerId, type, LAST_SUCCESS), now)
            .apply()
    }

    fun recordFailure(peerId: String, type: TransportType, now: Long = System.currentTimeMillis()): Long {
        val count = (failureCount(peerId, type) + 1).coerceAtMost(MAX_FAILURES)
        val backoffMs = (BASE_BACKOFF_MS shl (count - 1)).coerceAtMost(MAX_BACKOFF_MS)
        val cooldownUntil = now + backoffMs
        prefs.edit()
            .putInt(key(peerId, type, FAILURES), count)
            .putLong(key(peerId, type, COOLDOWN_UNTIL), cooldownUntil)
            .putLong(key(peerId, type, LAST_FAILURE), now)
            .apply()
        return cooldownUntil
    }

    fun lastSuccessAt(peerId: String, type: TransportType): Long =
        prefs.getLong(key(peerId, type, LAST_SUCCESS), 0L)

    private fun key(peerId: String, type: TransportType, field: String): String {
        val raw = "$peerId|$type"
        val encoded = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "$encoded.$field"
    }

    companion object {
        private const val PREFS = "meshlink_transport_path_health"
        private const val FAILURES = "failures"
        private const val COOLDOWN_UNTIL = "cooldown_until"
        private const val LAST_SUCCESS = "last_success"
        private const val LAST_FAILURE = "last_failure"
        private const val MAX_FAILURES = 8
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }
}

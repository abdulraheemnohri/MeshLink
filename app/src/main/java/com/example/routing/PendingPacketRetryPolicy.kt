package com.example.routing

/**
 * Durable retry schedule for queued DTN packets.
 *
 * attemptCount is the number of attempts already started. The first attempt is
 * immediate; each later attempt waits an exponentially increasing interval.
 */
object PendingPacketRetryPolicy {
    const val BASE_DELAY_MS = 15_000L
    const val MAX_DELAY_MS = 15L * 60L * 1_000L

    fun retryDelayMs(attemptCount: Int): Long {
        if (attemptCount <= 0) return 0L
        var delay = BASE_DELAY_MS
        repeat((attemptCount - 1).coerceAtMost(30)) {
            if (delay >= MAX_DELAY_MS / 2L) return MAX_DELAY_MS
            delay *= 2L
        }
        return delay.coerceAtMost(MAX_DELAY_MS)
    }

    fun shouldAttempt(attemptCount: Int, lastAttemptAt: Long, now: Long): Boolean {
        if (attemptCount <= 0 || lastAttemptAt <= 0L) return true
        if (now < lastAttemptAt) return false
        return now - lastAttemptAt >= retryDelayMs(attemptCount)
    }
}

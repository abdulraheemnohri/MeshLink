package com.example.routing

/**
 * Persistent retry schedule for the Room-backed DTN outbox.
 *
 * The attempt timestamp is stored before the next maintenance pass, so process death
 * cannot reset a packet to an immediate retry loop. Expiry remains the terminal bound.
 */
object StoreForwardRetryPolicy {
    const val BASE_DELAY_MS = 15_000L
    const val MAX_DELAY_MS = 5 * 60_000L

    fun delayForAttempt(attemptCount: Int): Long {
        if (attemptCount <= 0) return 0L
        var delay = BASE_DELAY_MS
        repeat((attemptCount - 1).coerceAtMost(20)) {
            delay = (delay * 2).coerceAtMost(MAX_DELAY_MS)
        }
        return delay
    }

    fun isDue(now: Long, lastAttemptAt: Long, attemptCount: Int): Boolean {
        if (attemptCount <= 0 || lastAttemptAt <= 0L) return true
        val elapsed = (now - lastAttemptAt).coerceAtLeast(0L)
        return elapsed >= delayForAttempt(attemptCount)
    }
}

package com.example.background

import kotlinx.coroutines.CancellationException

/**
 * Converts ordinary relay failures to Result while preserving structured cancellation.
 */
internal object RelayTaskGuard {
    suspend fun <T> run(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}

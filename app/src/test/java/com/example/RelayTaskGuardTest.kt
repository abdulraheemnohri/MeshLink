package com.example

import com.example.background.RelayTaskGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTaskGuardTest {
    @Test
    fun ordinaryFailureIsReturnedWithoutThrowing() = runBlocking<Unit> {
        val result = RelayTaskGuard.run<Int> { error("radio failed") }
        assertTrue(result.isFailure)
        assertEquals("radio failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun cancellationIsPropagated() = runBlocking<Unit> {
        var propagated = false
        try {
            RelayTaskGuard.run { throw CancellationException("service stopping") }
        } catch (cancelled: CancellationException) {
            propagated = cancelled.message == "service stopping"
        }
        assertTrue(propagated)
    }

    @Test
    fun successfulTaskReturnsItsValue() = runBlocking<Unit> {
        assertEquals(42, RelayTaskGuard.run { 42 }.getOrThrow())
    }
}

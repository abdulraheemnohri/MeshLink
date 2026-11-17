package com.example.files

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedFileChunkTest {
    @Test
    fun acceptsOnlyTheExactStoredChunkAtItsOffset() {
        val file = File.createTempFile("mesh-verified-", ".bin")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
            assertTrue(VerifiedFileChunk.matches(file, 0L, byteArrayOf(1, 2, 3)))
            assertTrue(VerifiedFileChunk.matches(file, 3L, byteArrayOf(4, 5, 6)))
            assertFalse(VerifiedFileChunk.matches(file, 0L, byteArrayOf(1, 2, 9)))
            assertFalse(VerifiedFileChunk.matches(file, 4L, byteArrayOf(5, 6, 7)))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsMissingFilesAndNegativeOffsets() {
        val missing = File.createTempFile("mesh-missing-", ".bin").apply {
            delete()
        }
        assertFalse(VerifiedFileChunk.matches(missing, 0L, byteArrayOf(1)))
        val file = File.createTempFile("mesh-offset-", ".bin")
        try {
            file.writeBytes(byteArrayOf(1))
            assertFalse(VerifiedFileChunk.matches(file, -1L, byteArrayOf(1)))
        } finally {
            file.delete()
        }
    }
}

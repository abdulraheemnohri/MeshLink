package com.example.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class VerifiedFileIntegrityTest {
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun acceptsExactLengthAndHash() {
        val bytes = "MeshLink verified payload".toByteArray()
        val file = File.createTempFile("meshlink-integrity", ".bin")
        try {
            file.writeBytes(bytes)
            assertTrue(VerifiedFileIntegrity.matches(file, bytes.size.toLong(), sha256(bytes)))
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsChangedContent() {
        val file = File.createTempFile("meshlink-integrity", ".bin")
        try {
            file.writeText("tampered")
            assertFalse(VerifiedFileIntegrity.matches(file, file.length(), sha256("original".toByteArray())))
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsLengthMismatch() {
        val bytes = byteArrayOf(1, 2, 3)
        val file = File.createTempFile("meshlink-integrity", ".bin")
        try {
            file.writeBytes(bytes)
            assertFalse(VerifiedFileIntegrity.matches(file, 99L, sha256(bytes)))
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsMissingFileAndMalformedHash() {
        val missing = File.createTempFile("meshlink-integrity", ".bin")
        missing.delete()
        assertFalse(VerifiedFileIntegrity.matches(missing, 0L, "0".repeat(64)))
        val file = File.createTempFile("meshlink-integrity", ".bin")
        try {
            file.writeText("payload")
            assertFalse(VerifiedFileIntegrity.matches(file, file.length(), "not-a-sha256"))
        } finally {
            file.delete()
        }
    }
}

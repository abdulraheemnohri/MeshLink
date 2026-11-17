package com.example.files

import java.io.File
import java.security.MessageDigest

/** Revalidates a persisted download immediately before it is exported or shared. */
internal object VerifiedFileIntegrity {
    fun matches(file: File, expectedLength: Long, expectedSha256: String): Boolean {
        if (expectedLength < 0L || !file.isFile || file.length() != expectedLength) return false
        if (!expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expectedSha256, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }
}

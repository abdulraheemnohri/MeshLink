package com.example.files

import java.io.File
import java.io.RandomAccessFile

/**
 * Confirms that a retransmitted chunk is byte-for-byte identical to the
 * corresponding range of an already verified destination file.
 */
internal object VerifiedFileChunk {
    fun matches(file: File, offset: Long, bytes: ByteArray): Boolean {
        if (!file.isFile || offset < 0L) return false
        return runCatching {
            RandomAccessFile(file, "r").use { source ->
                if (offset > source.length() || bytes.size.toLong() > source.length() - offset) {
                    return@use false
                }
                val existing = ByteArray(bytes.size)
                source.seek(offset)
                source.readFully(existing)
                existing.contentEquals(bytes)
            }
        }.getOrDefault(false)
    }
}

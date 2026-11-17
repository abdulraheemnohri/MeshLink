package com.example

import com.example.core.protocol.BleFrameCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Test

class BleFrameCodecTest {
    @Test
    fun fragmentsAndReassemblesLargePayloadOutOfOrder() {
        val original = ByteArray(8_192) { (it % 251).toByte() }
        val frames = BleFrameCodec.fragment(original, 185)
        assertTrue(frames.size > 1)

        val reassembler = BleFrameCodec.Reassembler()
        var result: ByteArray? = null
        frames.reversed().forEach { frame ->
            val completed = reassembler.accept(frame)
            if (completed != null) result = completed
        }
        assertArrayEquals(original, result)
    }

    @Test
    fun reassemblyTimeoutRefreshesAsFramesArrive() {
        var now = 0L
        val original = ByteArray(120) { it.toByte() }
        val frames = BleFrameCodec.fragment(original, 64)
        val reassembler = BleFrameCodec.Reassembler(
            transferTimeoutMs = 100L,
            clock = { now }
        )

        assertNull(reassembler.accept(frames[0]))
        now = 90L
        assertNull(reassembler.accept(frames[1]))
        now = 180L
        val result = reassembler.accept(frames[2])
        assertArrayEquals(original, result)
    }

    @Test
    fun corruptedFrameFailsCrcValidation() {
        val frames = BleFrameCodec.fragment(ByteArray(120) { it.toByte() }, 64)
        val corrupted = frames.first().clone()
        corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 0x01).toByte()
        val reassembler = BleFrameCodec.Reassembler()
        assertNull(reassembler.accept(corrupted))
    }

    @Test
    fun rejectsAggregateChunkBytesBeyondDeclaredTransferLength() {
        val reassembler = BleFrameCodec.Reassembler()
        val oversizedA = frame(transferId = 991, index = 0, count = 2, totalLength = 100, chunk = ByteArray(100) { 1 })
        val oversizedB = frame(transferId = 991, index = 1, count = 2, totalLength = 100, chunk = ByteArray(100) { 2 })

        assertNull(reassembler.accept(oversizedA))
        assertNull(reassembler.accept(oversizedB))

        // A rejected transfer must be discarded, allowing a valid retry with the same ID.
        val original = ByteArray(100) { it.toByte() }
        val validA = frame(transferId = 991, index = 0, count = 2, totalLength = 100, chunk = original.copyOfRange(0, 50))
        val validB = frame(transferId = 991, index = 1, count = 2, totalLength = 100, chunk = original.copyOfRange(50, 100))
        assertNull(reassembler.accept(validA))
        assertArrayEquals(original, reassembler.accept(validB))
    }


    @Test
    fun reassemblerEvictsOldestIncompleteTransferAtConfiguredLimit() {
        val reassembler = BleFrameCodec.Reassembler(maxTransfers = 1)
        val first = BleFrameCodec.fragment(ByteArray(100) { 1 }, 64)
        val secondPayload = ByteArray(100) { 2 }
        val second = BleFrameCodec.fragment(secondPayload, 64)

        assertNull(reassembler.accept(first[0]))
        assertNull(reassembler.accept(second[0]))
        // The first transfer was evicted to keep the per-peer in-flight budget bounded.
        assertNull(reassembler.accept(first[1]))
        assertArrayEquals(secondPayload, reassembler.accept(second[1]))
    }

    @Test
    fun malformedFrameIsIgnored() {
        val reassembler = BleFrameCodec.Reassembler()
        assertNull(reassembler.accept(ByteArray(32)))
    }

    @Test
    fun incompleteTransferDoesNotEmitPayload() {
        val frames = BleFrameCodec.fragment(ByteArray(500) { it.toByte() }, 64)
        val reassembler = BleFrameCodec.Reassembler()
        frames.dropLast(1).forEach { assertNull(reassembler.accept(it)) }
    }

    private fun frame(
        transferId: Int,
        index: Int,
        count: Int,
        totalLength: Int,
        chunk: ByteArray
    ): ByteArray {
        val crc = CRC32().apply { update(chunk) }.value.toInt()
        return ByteBuffer.allocate(18 + chunk.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(0x4D4C.toShort())
            .putInt(transferId)
            .putShort(index.toShort())
            .putShort(count.toShort())
            .putInt(totalLength)
            .putInt(crc)
            .put(chunk)
            .array()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMtuTooSmallForHeader() {
        BleFrameCodec.fragment(byteArrayOf(1), 14)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedPayload() {
        BleFrameCodec.fragment(ByteArray(BleFrameCodec.MAX_PACKET_BYTES + 1), 185)
    }
}

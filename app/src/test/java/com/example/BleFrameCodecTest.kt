package com.example

import com.example.core.protocol.BleFrameCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun incompleteTransferDoesNotEmitPayload() {
        val frames = BleFrameCodec.fragment(ByteArray(500) { it.toByte() }, 64)
        val reassembler = BleFrameCodec.Reassembler()
        frames.dropLast(1).forEach { assertNull(reassembler.accept(it)) }
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

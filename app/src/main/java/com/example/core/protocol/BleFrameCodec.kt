package com.example.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

/**
 * Application-level framing for BLE GATT characteristic writes.
 *
 * Each frame has an 18-byte header: magic(2), transferId(4), index(2),
 * frameCount(2), totalLength(4), chunk CRC32(4). A transfer is accepted only when every
 * frame is present and the reconstructed size matches totalLength.
 */
object BleFrameCodec {
    private const val MAGIC: Short = 0x4D4C
    private const val HEADER_BYTES = 18
    const val MAX_PACKET_BYTES = 256 * 1024
    const val MAX_FRAMES = 2048

    private val nextTransferId = AtomicInteger(1)

    fun fragment(payload: ByteArray, maxCharacteristicValueBytes: Int): List<ByteArray> {
        require(payload.isNotEmpty()) { "BLE payload must not be empty" }
        require(payload.size <= MAX_PACKET_BYTES) { "BLE payload exceeds limit" }
        require(maxCharacteristicValueBytes > HEADER_BYTES) {
            "Negotiated BLE characteristic value is too small for framing"
        }

        val chunkSize = maxCharacteristicValueBytes - HEADER_BYTES
        val count = (payload.size + chunkSize - 1) / chunkSize
        require(count <= MAX_FRAMES) { "BLE payload requires too many frames" }
        val transferId = nextTransferId.getAndIncrement()

        return (0 until count).map { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, payload.size)
            val chunk = payload.copyOfRange(start, end)
            ByteBuffer.allocate(HEADER_BYTES + chunk.size)
                .order(ByteOrder.BIG_ENDIAN)
                .putShort(MAGIC)
                .putInt(transferId)
                .putShort(index.toShort())
                .putShort(count.toShort())
                .putInt(payload.size)
                .putInt(crc32(chunk).toInt())
                .put(chunk)
                .array()
        }
    }

    private fun crc32(bytes: ByteArray): Long =
        CRC32().apply { update(bytes) }.value

    class Reassembler(
        private val maxTransfers: Int = 8,
        private val transferTimeoutMs: Long = 30_000L,
        private val clock: () -> Long = System::currentTimeMillis
    ) {
        private data class Pending(
            val frameCount: Int,
            val totalLength: Int,
            var lastUpdatedAt: Long,
            val frames: Array<ByteArray?>
        )

        private val pending = ConcurrentHashMap<Int, Pending>()

        @Synchronized
        fun accept(frame: ByteArray): ByteArray? {
            if (frame.size <= HEADER_BYTES) return null
            val input = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
            if (input.short != MAGIC) return null
            val transferId = input.int
            val index = input.short.toInt() and 0xFFFF
            val count = input.short.toInt() and 0xFFFF
            val totalLength = input.int
            val expectedCrc = input.int.toLong() and 0xFFFFFFFFL
            val chunk = ByteArray(input.remaining()).also(input::get)

            if (count !in 1..MAX_FRAMES || index !in 0 until count) return null
            if (totalLength !in 1..MAX_PACKET_BYTES) return null
            if (chunk.isEmpty() || chunk.size > totalLength || BleFrameCodec.crc32(chunk) != expectedCrc) return null

            val now = clock()
            pending.entries.removeIf { now - it.value.lastUpdatedAt > transferTimeoutMs }
            val transfer = pending[transferId] ?: run {
                if (pending.size >= maxTransfers) {
                    val oldest = pending.entries.minByOrNull { it.value.lastUpdatedAt }?.key
                    if (oldest != null) pending.remove(oldest)
                }
                Pending(count, totalLength, now, arrayOfNulls(count)).also {
                    pending[transferId] = it
                }
            }

            if (transfer.frameCount != count || transfer.totalLength != totalLength) {
                pending.remove(transferId)
                return null
            }

            transfer.lastUpdatedAt = now
            val previous = transfer.frames[index]
            if (previous != null && !previous.contentEquals(chunk)) {
                pending.remove(transferId)
                return null
            }
            transfer.frames[index] = chunk
            if (transfer.frames.any { it == null }) return null

            val result = ByteArray(totalLength)
            var offset = 0
            transfer.frames.forEach { part ->
                val bytes = part ?: return null
                if (offset + bytes.size > totalLength) {
                    pending.remove(transferId)
                    return null
                }
                bytes.copyInto(result, offset)
                offset += bytes.size
            }
            pending.remove(transferId)
            return if (offset == totalLength) result else null
        }
    }
}

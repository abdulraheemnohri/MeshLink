package com.example.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.example.core.crypto.MeshCryptoEngine
import com.example.core.protocol.MeshPacket
import com.example.core.protocol.PacketType
import com.example.core.protocol.RoutingStrategy
import com.example.data.entities.FileChunkEntity
import com.example.data.entities.FileTransferEntity
import com.example.data.repository.MeshRepository
import com.example.routing.MeshRoutingEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FileTransferManager(
    private val context: Context,
    private val repository: MeshRepository,
    private val crypto: MeshCryptoEngine,
    private val routingEngine: MeshRoutingEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pausedTransfers = ConcurrentHashMap<String, Boolean>()
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    init {
        scope.launch {
            routingEngine.incomingFilePacket.collect { incoming ->
                handleIncomingFilePacket(incoming.packet.senderId, incoming.payloadJson)
            }
        }
    }

    suspend fun queueOutgoingFile(uri: Uri, peerId: String): FileTransferEntity? {
        val resolver = context.contentResolver
        var fileName = "mesh_file_${System.currentTimeMillis()}"
        var fileSize = 0L

        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
            }
        }
        fileName = fileName.substringAfterLast('/').substringAfterLast('\\').filter { it.code >= 32 && it != '/' && it != '\\' }.take(120).ifBlank { "received_file" }
        if (fileSize > MAX_FILE_BYTES) return null
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"

        // Copy to app-private cache so resumable chunk reads survive URI permission expiration
        val transferId = "xfer_${UUID.randomUUID().toString().take(8)}"
        val localDir = File(context.filesDir, "mesh_transfers").apply { mkdirs() }
        val localFile = File(localDir, "${transferId}_$fileName")

        val shaDigest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(localFile).use { output ->
                val buf = ByteArray(16384)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    if (localFile.length() + read > MAX_FILE_BYTES) {
                        localFile.delete()
                        return null
                    }
                    output.write(buf, 0, read)
                    shaDigest.update(buf, 0, read)
                }
            }
        } ?: return null

        val actualSize = localFile.length()
        if (actualSize > MAX_FILE_BYTES) {
            localFile.delete()
            return null
        }
        if (fileSize <= 0L) fileSize = actualSize
        val fullSha256 = shaDigest.digest().joinToString("") { "%02x".format(it) }
        val totalChunks = ((actualSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt().coerceAtLeast(1)

        val peer = repository.getPeerById(peerId)
        val transfer = FileTransferEntity(
            transferId = transferId,
            peerId = peerId,
            peerAlias = peer?.alias ?: peerId,
            fileName = fileName,
            mimeType = mimeType,
            localUriOrPath = localFile.absolutePath,
            totalBytes = actualSize,
            totalChunks = totalChunks,
            completedChunks = 0,
            sha256 = fullSha256,
            isOutgoing = true,
            transportType = peer?.activeTransport ?: "LOCAL_LAN",
            status = "TRANSFERRING"
        )
        repository.upsertFileTransfer(transfer)

        val chunks = buildChunkRecords(transferId, localFile)
        repository.insertFileChunks(chunks)

        scope.launch {
            sendOfferAndChunks(transfer, localFile)
        }
        return transfer
    }

    suspend fun pauseTransfer(transferId: String) {
        pausedTransfers[transferId] = true
        val existing = repository.getFileTransferById(transferId) ?: return
        repository.upsertFileTransfer(
            existing.copy(status = "PAUSED", updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun resumeTransfer(transferId: String) {
        pausedTransfers[transferId] = false
        val existing = repository.getFileTransferById(transferId) ?: return
        val file = File(existing.localUriOrPath)
        if (!file.exists()) {
            repository.upsertFileTransfer(
                existing.copy(status = "FAILED", updatedAt = System.currentTimeMillis())
            )
            return
        }
        repository.upsertFileTransfer(
            existing.copy(status = "TRANSFERRING", updatedAt = System.currentTimeMillis())
        )
        scope.launch {
            sendOfferAndChunks(existing, file)
        }
    }

    private suspend fun sendOfferAndChunks(transfer: FileTransferEntity, localFile: File) {
        val local = repository.ensureInitialized()
        val peer = repository.getPeerById(transfer.peerId) ?: return
        val completeAck = CompletableDeferred<Boolean>()
        pendingAcks["${transfer.transferId}:complete"] = completeAck
        if (peer.agreementPublicKey.isBlank()) {
            repository.upsertFileTransfer(transfer.copy(status = "PAUSED", updatedAt = System.currentTimeMillis()))
            return
        }

        val offerJson = JSONObject().apply {
            put("kind", "OFFER")
            put("transferId", transfer.transferId)
            put("fileName", transfer.fileName)
            put("mimeType", transfer.mimeType)
            put("totalBytes", transfer.totalBytes)
            put("totalChunks", transfer.totalChunks)
            put("sha256", transfer.sha256)
        }.toString()

        val offerPacket = crypto.signPacket(
            MeshPacket(
                senderId = local.deviceId,
                destinationId = transfer.peerId,
                packetType = PacketType.FILE_OFFER,
                routingStrategy = RoutingStrategy.STORE_AND_FORWARD,
                encryptedPayload = crypto.encryptForPeer(offerJson, peer.agreementPublicKey, peer.ratchetCounter),
                visitedPeers = listOf(local.deviceId)
            )
        )
        val offerAck = CompletableDeferred<Boolean>()
        pendingAcks["${transfer.transferId}:offer"] = offerAck
        routingEngine.sendRawSignedPacket(offerPacket)
        if (withTimeoutOrNull(ACK_TIMEOUT_MS) { offerAck.await() } != true) {
            pendingAcks.remove("${transfer.transferId}:offer")
            val current = repository.getFileTransferById(transfer.transferId) ?: transfer
            if (current.status != "FAILED") {
                repository.upsertFileTransfer(current.copy(status = "PAUSED", updatedAt = System.currentTimeMillis()))
            }
            return
        }

        val remainingChunks = repository.getIncompleteChunks(transfer.transferId)
        val completed = transfer.totalChunks - remainingChunks.size
        RandomAccessFile(localFile, "r").use { source ->
            for (chunk in remainingChunks) {
                if (pausedTransfers[transfer.transferId] == true) return
                if (chunk.byteOffset < 0L || chunk.byteLength < 0 || chunk.byteOffset + chunk.byteLength > localFile.length()) {
                    repository.upsertFileTransfer(transfer.copy(status = "FAILED", updatedAt = System.currentTimeMillis()))
                    return
                }
                val slice = ByteArray(chunk.byteLength)
                source.seek(chunk.byteOffset)
                source.readFully(slice)
                if (crypto.sha256Hex(slice) != chunk.chunkSha256) {
                    repository.logSecurityEvent(
                        eventType = "LOCAL_CHUNK_HASH_FAIL",
                        peerId = transfer.peerId,
                        severity = "ALERT",
                        summary = "Local source chunk ${chunk.chunkIndex} changed during transfer"
                    )
                    repository.upsertFileTransfer(transfer.copy(status = "FAILED", updatedAt = System.currentTimeMillis()))
                    return
                }
                val chunkJson = JSONObject().apply {
                    put("kind", "CHUNK")
                    put("transferId", transfer.transferId)
                    put("index", chunk.chunkIndex)
                    put("chunkSha256", chunk.chunkSha256)
                    put("data", Base64.encodeToString(slice, Base64.NO_WRAP))
                }.toString()

                val chunkPacket = crypto.signPacket(
                    MeshPacket(
                        senderId = local.deviceId,
                        destinationId = transfer.peerId,
                        packetType = PacketType.FILE_CHUNK,
                        routingStrategy = RoutingStrategy.STORE_AND_FORWARD,
                        encryptedPayload = crypto.encryptForPeer(chunkJson, peer.agreementPublicKey, peer.ratchetCounter),
                        visitedPeers = listOf(local.deviceId)
                    )
                )
                val ackKey = "${transfer.transferId}:${chunk.chunkIndex}"
                val ack = CompletableDeferred<Boolean>()
                pendingAcks[ackKey] = ack
                routingEngine.sendRawSignedPacket(chunkPacket)
                if (withTimeoutOrNull(ACK_TIMEOUT_MS) { ack.await() } != true) {
                    pendingAcks.remove(ackKey)
                    repository.upsertFileTransfer(
                        transfer.copy(
                            completedChunks = transfer.totalChunks - repository.getIncompleteChunks(transfer.transferId).size,
                            status = "PAUSED",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    return
                }
            }
        }

        val finalResult = withTimeoutOrNull(COMPLETE_TIMEOUT_MS) { completeAck.await() }
        if (finalResult != true) {
            pendingAcks.remove("${transfer.transferId}:complete")
            val current = repository.getFileTransferById(transfer.transferId) ?: transfer
            repository.upsertFileTransfer(
                current.copy(
                    status = if (finalResult == false) "FAILED" else "TRANSFERRING",
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun handleIncomingFilePacket(senderId: String, payload: JSONObject) {
        val kind = payload.optString("kind")
        val transferId = payload.optString("transferId")
        if (!transferId.matches(Regex("[A-Za-z0-9_-]{1,96}"))) return

        if (kind == "ACK") {
            val transfer = repository.getFileTransferById(transferId) ?: return
            if (!transfer.isOutgoing || transfer.peerId != senderId) return
            val index = payload.optInt("index", Int.MIN_VALUE)
            when (index) {
                -2 -> {
                    repository.upsertFileTransfer(
                        transfer.copy(status = "VERIFIED", completedChunks = transfer.totalChunks, updatedAt = System.currentTimeMillis())
                    )
                    pendingAcks.remove("$transferId:complete")?.complete(true)
                }
                -1 -> pendingAcks.remove("$transferId:offer")?.complete(true)
                -3 -> {
                    repository.upsertFileTransfer(
                        transfer.copy(status = "FAILED", updatedAt = System.currentTimeMillis())
                    )
                    pendingAcks.remove("$transferId:offer")?.complete(false)
                    pendingAcks.remove("$transferId:complete")?.complete(false)
                }
                else -> if (index in 0 until transfer.totalChunks) {
                    repository.markChunkCompleted(transferId, index)
                    val remaining = repository.getIncompleteChunks(transferId).size
                    repository.upsertFileTransfer(
                        transfer.copy(
                            completedChunks = transfer.totalChunks - remaining,
                            status = "TRANSFERRING",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    pendingAcks.remove("$transferId:$index")?.complete(true)
                }
            }
            return
        }

        if (kind == "OFFER") {
            val peer = repository.getPeerById(senderId)
            val totalBytes = payload.optLong("totalBytes", -1L)
            val totalChunks = payload.optInt("totalChunks", -1)
            val expectedHash = payload.optString("sha256", "")
            if (totalBytes !in 0..MAX_FILE_BYTES || totalChunks !in 1..MAX_CHUNKS ||
                totalChunks != ((totalBytes + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt().coerceAtLeast(1) ||
                !expectedHash.matches(Regex("[a-fA-F0-9]{64}")) || peer?.agreementPublicKey.isNullOrBlank()
            ) return
            val safeName = payload.optString("fileName", "received_file")
                .substringAfterLast('/').substringAfterLast('\\')
                .filter { it.code >= 32 && it != '/' && it != '\\' }
                .take(120).ifBlank { "received_file" }
            val dir = File(context.filesDir, "mesh_received").apply { mkdirs() }
            val destFile = File(dir, "${transferId}_$safeName")
            val transfer = FileTransferEntity(
                transferId = transferId,
                peerId = senderId,
                peerAlias = peer?.alias ?: senderId,
                fileName = safeName,
                mimeType = payload.optString("mimeType", "application/octet-stream"),
                localUriOrPath = destFile.absolutePath,
                totalBytes = totalBytes,
                totalChunks = totalChunks,
                completedChunks = 0,
                sha256 = expectedHash.lowercase(),
                isOutgoing = false,
                status = "TRANSFERRING"
            )
            val existing = repository.getFileTransferById(transferId)
            if (existing != null) {
                if (existing.isOutgoing || existing.peerId != senderId ||
                    existing.totalBytes != totalBytes || existing.totalChunks != totalChunks ||
                    !existing.sha256.equals(expectedHash, true)
                ) return
                when (existing.status) {
                    "VERIFIED" -> {
                        sendFileAck(senderId, transferId, -1)
                        sendFileAck(senderId, transferId, -2)
                    }
                    "FAILED" -> sendFileAck(senderId, transferId, -3)
                    else -> sendFileAck(senderId, transferId, -1)
                }
                return
            }
            repository.upsertFileTransfer(transfer)
            sendFileAck(senderId, transferId, -1)
        } else if (kind == "CHUNK") {
            val transfer = repository.getFileTransferById(transferId) ?: return
            if (transfer.isOutgoing || transfer.peerId != senderId || transfer.status !in setOf("TRANSFERRING", "PAUSED")) return
            val index = payload.optInt("index", -1)
            val expectedChunkSha = payload.optString("chunkSha256", "")
            val dataB64 = payload.optString("data", "")
            if (index !in 0 until transfer.totalChunks || dataB64.length > MAX_ENCODED_CHUNK) return

            val expectedLength = minOf(CHUNK_SIZE.toLong(), (transfer.totalBytes - index.toLong() * CHUNK_SIZE).coerceAtLeast(0L)).toInt()
            if (expectedLength > 0 && dataB64.isBlank()) return
            val bytes = runCatching { Base64.decode(dataB64, Base64.NO_WRAP) }.getOrNull() ?: return
            if (bytes.size != expectedLength || expectedChunkSha.length != 64) return
            if (expectedChunkSha.isNotBlank() && crypto.sha256Hex(bytes) != expectedChunkSha) {
                repository.logSecurityEvent(
                    eventType = "CHUNK_HASH_FAIL",
                    peerId = senderId,
                    severity = "WARN",
                    summary = "Chunk $index SHA-256 mismatch on transfer $transferId"
                )
                return
            }

            val chunkDir = File(context.filesDir, "mesh_chunks/$transferId").apply { mkdirs() }
            val partFile = File(chunkDir, "chunk_%05d.part".format(index))
            val tempFile = File(chunkDir, "chunk_%05d.tmp".format(index))
            tempFile.writeBytes(bytes)
            if (partFile.exists()) partFile.delete()
            if (!tempFile.renameTo(partFile)) {
                tempFile.delete()
                return
            }

            val receivedParts = (0 until transfer.totalChunks).count {
                File(chunkDir, "chunk_%05d.part".format(it)).isFile
            }
            sendFileAck(senderId, transferId, index)
            if (receivedParts >= transfer.totalChunks) {
                val outFile = File(transfer.localUriOrPath)
                val outStream = FileOutputStream(outFile)
                val digest = MessageDigest.getInstance("SHA-256")
                var assembledBytes = 0L
                outStream.use { output ->
                    for (i in 0 until transfer.totalChunks) {
                        val part = File(chunkDir, "chunk_%05d.part".format(i))
                        if (!part.isFile) return
                        part.inputStream().use { input ->
                            val buffer = ByteArray(16 * 1024)
                            var count: Int
                            while (input.read(buffer).also { count = it } != -1) {
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                assembledBytes += count
                            }
                        }
                    }
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                val verified = assembledBytes == transfer.totalBytes && actualHash.equals(transfer.sha256, true)
                repository.upsertFileTransfer(
                    transfer.copy(
                        completedChunks = transfer.totalChunks,
                        status = if (verified) "VERIFIED" else "FAILED",
                        updatedAt = System.currentTimeMillis()
                    )
                )
                if (verified) sendFileAck(senderId, transferId, -2)
                else {
                    sendFileAck(senderId, transferId, -3)
                    repository.logSecurityEvent(
                    eventType = "FILE_HASH_FAIL",
                    peerId = senderId,
                    severity = "ALERT",
                    summary = "Final file size/hash verification failed for $transferId"
                    )
                }
            } else {
                repository.upsertFileTransfer(
                    transfer.copy(
                        completedChunks = receivedParts,
                        status = "TRANSFERRING",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private suspend fun sendFileAck(destinationId: String, transferId: String, index: Int) {
        val local = repository.ensureInitialized()
        val peer = repository.getPeerById(destinationId) ?: return
        if (peer.agreementPublicKey.isBlank()) return
        val body = JSONObject().apply {
            put("kind", "ACK")
            put("transferId", transferId)
            put("index", index)
        }.toString()
        val packet = crypto.signPacket(
            MeshPacket(
                senderId = local.deviceId,
                destinationId = destinationId,
                packetType = PacketType.FILE_CHUNK,
                routingStrategy = RoutingStrategy.DIRECT,
                encryptedPayload = crypto.encryptForPeer(body, peer.agreementPublicKey, peer.ratchetCounter),
                visitedPeers = listOf(local.deviceId),
                priority = MeshPacket.PRIORITY_HIGH
            )
        )
        routingEngine.sendRawSignedPacket(packet)
    }

    private fun buildChunkRecords(transferId: String, file: File): List<FileChunkEntity> {
        if (file.length() == 0L) {
            return listOf(
                FileChunkEntity(
                    transferId = transferId,
                    chunkIndex = 0,
                    byteOffset = 0L,
                    byteLength = 0,
                    chunkSha256 = crypto.sha256Hex(ByteArray(0)),
                    completed = false
                )
            )
        }
        val list = mutableListOf<FileChunkEntity>()
        RandomAccessFile(file, "r").use { source ->
            var offset = 0L
            var index = 0
            while (offset < source.length()) {
                val length = minOf(CHUNK_SIZE.toLong(), source.length() - offset).toInt()
                val bytes = ByteArray(length)
                source.seek(offset)
                source.readFully(bytes)
                list += FileChunkEntity(
                    transferId = transferId,
                    chunkIndex = index,
                    byteOffset = offset,
                    byteLength = length,
                    chunkSha256 = crypto.sha256Hex(bytes),
                    completed = false
                )
                offset += length
                index++
            }
        }
        return list
    }

    companion object {
        private const val CHUNK_SIZE = 32 * 1024
        private const val MAX_FILE_BYTES = 256L * 1024L * 1024L
        private const val MAX_CHUNKS = 8192
        private const val MAX_ENCODED_CHUNK = ((CHUNK_SIZE + 2) / 3) * 4 + 16
        private const val ACK_TIMEOUT_MS = 45_000L
        private const val COMPLETE_TIMEOUT_MS = 120_000L
    }
}

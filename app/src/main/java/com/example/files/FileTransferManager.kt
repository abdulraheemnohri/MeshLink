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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
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
                    output.write(buf, 0, read)
                    shaDigest.update(buf, 0, read)
                }
            }
        } ?: return null

        val actualSize = localFile.length()
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
        routingEngine.sendRawSignedPacket(offerPacket)

        val remainingChunks = repository.getIncompleteChunks(transfer.transferId)
        val fileBytes = localFile.readBytes()
        var completed = transfer.totalChunks - remainingChunks.size

        for (chunk in remainingChunks) {
            if (pausedTransfers[transfer.transferId] == true) return
            val end = (chunk.byteOffset.toInt() + chunk.byteLength).coerceAtMost(fileBytes.size)
            val slice = fileBytes.copyOfRange(chunk.byteOffset.toInt(), end)
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
            routingEngine.sendRawSignedPacket(chunkPacket)
            repository.markChunkCompleted(transfer.transferId, chunk.chunkIndex)
            completed++
            val status = if (completed >= transfer.totalChunks) "VERIFIED" else "TRANSFERRING"
            repository.upsertFileTransfer(
                transfer.copy(
                    completedChunks = completed,
                    status = status,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun handleIncomingFilePacket(senderId: String, payload: JSONObject) {
        val kind = payload.optString("kind")
        val transferId = payload.optString("transferId")
        if (transferId.isBlank()) return

        if (kind == "OFFER") {
            val peer = repository.getPeerById(senderId)
            val dir = File(context.filesDir, "mesh_received").apply { mkdirs() }
            val fileName = payload.optString("fileName", "received_file")
            val destFile = File(dir, "${transferId}_$fileName")
            val totalChunks = payload.optInt("totalChunks", 1)
            val transfer = FileTransferEntity(
                transferId = transferId,
                peerId = senderId,
                peerAlias = peer?.alias ?: senderId,
                fileName = fileName,
                mimeType = payload.optString("mimeType", "application/octet-stream"),
                localUriOrPath = destFile.absolutePath,
                totalBytes = payload.optLong("totalBytes", 0L),
                totalChunks = totalChunks,
                completedChunks = 0,
                sha256 = payload.optString("sha256", ""),
                isOutgoing = false,
                status = "TRANSFERRING"
            )
            repository.upsertFileTransfer(transfer)
        } else if (kind == "CHUNK") {
            val transfer = repository.getFileTransferById(transferId) ?: return
            val index = payload.optInt("index", -1)
            val expectedChunkSha = payload.optString("chunkSha256", "")
            val dataB64 = payload.optString("data", "")
            if (index < 0 || dataB64.isBlank()) return

            val bytes = Base64.decode(dataB64, Base64.NO_WRAP)
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
            File(chunkDir, "chunk_%05d.part".format(index)).writeBytes(bytes)

            val receivedParts = chunkDir.listFiles()?.size ?: 0
            if (receivedParts >= transfer.totalChunks) {
                val outFile = File(transfer.localUriOrPath)
                val outStream = ByteArrayOutputStream()
                for (i in 0 until transfer.totalChunks) {
                    val part = File(chunkDir, "chunk_%05d.part".format(i))
                    if (part.exists()) outStream.write(part.readBytes())
                }
                val assembled = outStream.toByteArray()
                outFile.writeBytes(assembled)
                val verified = transfer.sha256.isBlank() || crypto.sha256Hex(assembled) == transfer.sha256
                repository.upsertFileTransfer(
                    transfer.copy(
                        completedChunks = transfer.totalChunks,
                        status = if (verified) "VERIFIED" else "FAILED",
                        updatedAt = System.currentTimeMillis()
                    )
                )
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

    private fun buildChunkRecords(transferId: String, file: File): List<FileChunkEntity> {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) {
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
        var offset = 0
        var idx = 0
        while (offset < bytes.size) {
            val len = minOf(CHUNK_SIZE, bytes.size - offset)
            val slice = bytes.copyOfRange(offset, offset + len)
            list += FileChunkEntity(
                transferId = transferId,
                chunkIndex = idx,
                byteOffset = offset.toLong(),
                byteLength = len,
                chunkSha256 = crypto.sha256Hex(slice),
                completed = false
            )
            offset += len
            idx++
        }
        return list
    }

    companion object {
        private const val CHUNK_SIZE = 32 * 1024
    }
}

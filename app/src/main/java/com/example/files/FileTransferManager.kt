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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
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
    private val activeTransfers = ConcurrentHashMap.newKeySet<String>()
    private val startupRecovery = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try { recoverInterruptedTransfers() } finally { startupRecovery.complete(Unit) }
        }
        scope.launch {
            startupRecovery.await()
            routingEngine.sessionEstablished.collect { peerId ->
                val transfers = repository.transfersFlow.first()
                transfers.filter { it.isOutgoing && it.peerId == peerId && it.status == "PAUSED" }
                    .forEach { transfer -> resumeTransfer(transfer.transferId) }
            }
        }
        scope.launch {
            startupRecovery.await()
            routingEngine.incomingFilePacket.collect { incoming ->
                try {
                    handleIncomingFilePacket(incoming.packet.senderId, incoming.payloadJson)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    repository.logNetworkEvent(
                        transportType = incoming.packet.packetType.name,
                        eventType = "FILE_RX_ERR",
                        peerId = incoming.packet.senderId,
                        summary = "Rejected file-transfer packet after processing error: ${error.message}"
                    )
                }
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

        launchOutgoingTransfer(transfer, localFile)
        return transfer
    }

    /** Export through the system document picker; works on Android versions below 10 too. */
    suspend fun exportVerifiedToUri(transferId: String, destination: Uri): Boolean {
        val transfer = repository.getFileTransferById(transferId) ?: return false
        if (transfer.isOutgoing || transfer.status != "VERIFIED") return false
        val source = File(transfer.localUriOrPath)
        if (!VerifiedFileIntegrity.matches(source, transfer.totalBytes, transfer.sha256)) {
            repository.logSecurityEvent(eventType = "EXPORT_HASH_FAIL", peerId = transfer.peerId, severity = "ALERT", summary = "Blocked SAF export because verified file changed on disk: " + transferId)
            return false
        }
        return try {
            val output = context.contentResolver.openOutputStream(destination, "w")
                ?: throw java.io.IOException("Document provider did not open destination")
            var copied = 0L
            output.use { sink ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        copied += count
                    }
                }
            }
            if (copied != transfer.totalBytes) throw java.io.IOException("Export length mismatch")
            repository.logNetworkEvent(transportType = transfer.transportType, eventType = "FILE_EXPORT_OK", peerId = transfer.peerId, summary = "Exported verified file " + transfer.fileName + " through SAF")
            true
        } catch (error: Exception) {
            runCatching { context.contentResolver.delete(destination, null, null) }
            repository.logNetworkEvent(transportType = transfer.transportType, eventType = "FILE_EXPORT_ERR", peerId = transfer.peerId, summary = "SAF export failed for " + transfer.fileName + ": " + error.message)
            false
        }
    }
    /** Export a received file only after re-checking its persisted size and SHA-256. */
    suspend fun exportVerifiedDownload(transferId: String): Uri? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return null
        val transfer = repository.getFileTransferById(transferId) ?: return null
        if (transfer.isOutgoing || transfer.status != "VERIFIED") return null
        val source = File(transfer.localUriOrPath)
        if (!source.isFile || source.length() != transfer.totalBytes) return null
        if (!VerifiedFileIntegrity.matches(source, transfer.totalBytes, transfer.sha256)) {
            repository.logSecurityEvent(eventType = "EXPORT_HASH_FAIL", peerId = transfer.peerId, severity = "ALERT", summary = "Blocked export because verified file changed on disk: $transferId")
            return null
        }
        val safeName = transfer.fileName.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.code >= 32 && it != '/' && it != '\\' }.take(120).ifBlank { "mesh_received_file" }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, transfer.mimeType)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/MeshLink")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val destination = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(destination, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: throw java.io.IOException("Unable to open Downloads destination")
            val publish = android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(destination, publish, null, null)
            destination
        } catch (error: Exception) {
            resolver.delete(destination, null, null)
            repository.logNetworkEvent(transportType = transfer.transportType, eventType = "FILE_EXPORT_ERR", peerId = transfer.peerId, summary = "Could not export verified file ${transfer.fileName}: " + error.message)
            null
        }
    }
    /** Reconcile in-flight database rows after Android kills the process. */
    suspend fun recoverInterruptedTransfers() {
        val transfers = repository.transfersFlow.first()
        transfers.filter { it.status == "TRANSFERRING" }.forEach { transfer ->
            if (transfer.isOutgoing) {
                val source = File(transfer.localUriOrPath)
                if (!source.isFile || source.length() != transfer.totalBytes) {
                    repository.upsertFileTransfer(
                        transfer.copy(status = "FAILED", updatedAt = System.currentTimeMillis())
                    )
                } else {
                    val remaining = repository.getIncompleteChunks(transfer.transferId).size
                    repository.upsertFileTransfer(
                        transfer.copy(
                            completedChunks = (transfer.totalChunks - remaining).coerceIn(0, transfer.totalChunks),
                            status = "PAUSED",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            } else {
                val chunkDir = File(context.filesDir, "mesh_chunks/" + transfer.transferId)
                val received = (0 until transfer.totalChunks).count { index ->
                    File(chunkDir, "chunk_%05d.part".format(index)).isFile
                }
                repository.upsertFileTransfer(
                    transfer.copy(
                        completedChunks = received.coerceIn(0, transfer.totalChunks),
                        status = "PAUSED",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    suspend fun pauseTransfer(transferId: String) {
        pausedTransfers[transferId] = true
        pendingAcks.entries.filter { it.key.startsWith("$transferId:") }.forEach {
            pendingAcks.remove(it.key)?.complete(false)
        }
        val existing = repository.getFileTransferById(transferId) ?: return
        repository.upsertFileTransfer(
            existing.copy(status = "PAUSED", updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun resumeTransfer(transferId: String) {
        pausedTransfers[transferId] = false
        pendingAcks.entries.filter { it.key.startsWith("$transferId:") }.forEach {
            pendingAcks.remove(it.key)?.complete(false)
        }
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
        launchOutgoingTransfer(existing, file)
    }

    private fun launchOutgoingTransfer(transfer: FileTransferEntity, file: File) {
        scope.launch {
            if (!activeTransfers.add(transfer.transferId)) return@launch
            try {
                sendOfferAndChunks(transfer, file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                repository.logNetworkEvent(
                    transportType = transfer.transportType,
                    eventType = "FILE_TX_ERR",
                    peerId = transfer.peerId,
                    summary = "File transfer ${transfer.transferId} paused after an error: ${error.message}"
                )
                val current = repository.getFileTransferById(transfer.transferId) ?: transfer
                if (current.status != "VERIFIED") {
                    repository.upsertFileTransfer(
                        current.copy(status = "PAUSED", updatedAt = System.currentTimeMillis())
                    )
                }
            } finally {
                pendingAcks.entries.filter { it.key.startsWith("${transfer.transferId}:") }.forEach {
                    pendingAcks.remove(it.key)?.complete(false)
                }
                activeTransfers.remove(transfer.transferId)
            }
        }
    }

    private suspend fun sendOfferAndChunks(transfer: FileTransferEntity, localFile: File) {
        val local = repository.ensureInitialized()
        val peer = repository.getPeerById(transfer.peerId) ?: run {
            repository.upsertFileTransfer(transfer.copy(status = "PAUSED", updatedAt = System.currentTimeMillis()))
            return
        }
        if (!peer.verifiedByQr || peer.blocked || peer.signingPublicKey.isBlank() || peer.agreementPublicKey.isBlank()) {
            repository.upsertFileTransfer(transfer.copy(status = "PAUSED", updatedAt = System.currentTimeMillis()))
            repository.logSecurityEvent(
                eventType = "FILE_SESSION_TRUST_REQUIRED",
                peerId = transfer.peerId,
                severity = "WARN",
                summary = "Paused file transfer because the destination is not QR-verified or is blocked"
            )
            return
        }
        val completeAck = CompletableDeferred<Boolean>()
        pendingAcks["${transfer.transferId}:complete"] = completeAck

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
                routingStrategy = RoutingStrategy.DIRECT,
                encryptedPayload = routingEngine.encryptForPeerSession(transfer.peerId, offerJson) ?: run {
                    repository.upsertFileTransfer(transfer.copy(status = "PAUSED", updatedAt = System.currentTimeMillis()))
                    return
                },
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
                        routingStrategy = RoutingStrategy.DIRECT,
                        encryptedPayload = routingEngine.encryptForPeerSession(transfer.peerId, chunkJson) ?: run {
                            repository.upsertFileTransfer(
                                transfer.copy(
                                    completedChunks = transfer.totalChunks - repository.getIncompleteChunks(transfer.transferId).size,
                                    status = "PAUSED",
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                            return
                        },
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
                    FileTransferNotifications.completed(context, transferId, transfer.fileName, incoming = false)
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
            if (transfer.isOutgoing || transfer.peerId != senderId ||
                transfer.status !in setOf("TRANSFERRING", "PAUSED", "VERIFIED")
            ) return
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

            // A chunk ACK or final ACK may be lost after the receiver has committed
            // the verified file. Re-ack only byte-identical chunks from that file.
            // This makes retransmission idempotent without reopening a verified file
            // to modification by a later packet.
            if (transfer.status == "VERIFIED") {
                val offset = index.toLong() * CHUNK_SIZE
                if (VerifiedFileChunk.matches(File(transfer.localUriOrPath), offset, bytes)) {
                    sendFileAck(senderId, transferId, index)
                    sendFileAck(senderId, transferId, -2)
                } else {
                    repository.logSecurityEvent(
                        eventType = "VERIFIED_CHUNK_REPLAY_MISMATCH",
                        peerId = senderId,
                        severity = "ALERT",
                        summary = "Rejected non-matching retransmitted chunk $index for verified transfer $transferId"
                    )
                }
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
                if (verified) {
                    FileTransferNotifications.completed(context, transferId, transfer.fileName, incoming = true)
                    sendFileAck(senderId, transferId, -2)
                } else {
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

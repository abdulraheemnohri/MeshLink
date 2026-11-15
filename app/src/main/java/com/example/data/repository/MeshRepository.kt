package com.example.data.repository

import com.example.core.crypto.LocalIdentityKeys
import com.example.core.crypto.MeshCryptoEngine
import com.example.core.crypto.PeerIdentityPayload
import com.example.data.database.AppDatabase
import com.example.data.entities.ConnectionEntity
import com.example.data.entities.DeviceIdentityEntity
import com.example.data.entities.EmergencyBroadcastEntity
import com.example.data.entities.FileChunkEntity
import com.example.data.entities.FileTransferEntity
import com.example.data.entities.GroupEntity
import com.example.data.entities.GroupMemberEntity
import com.example.data.entities.MessageEntity
import com.example.data.entities.NetworkEventEntity
import com.example.data.entities.PeerEntity
import com.example.data.entities.PendingPacketEntity
import com.example.data.entities.RouteEntity
import com.example.data.entities.SecurityEventEntity
import com.example.data.entities.SettingsEntity
import com.example.data.entities.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class MeshRepository(
    private val db: AppDatabase,
    private val crypto: MeshCryptoEngine
) {
    private val identityDao = db.deviceIdentityDao()
    private val peerDao = db.peerDao()
    private val connectionDao = db.connectionDao()
    private val messageDao = db.messageDao()
    private val groupDao = db.groupDao()
    private val emergencyDao = db.emergencyBroadcastDao()
    private val routeDao = db.routeDao()
    private val pendingPacketDao = db.pendingPacketDao()
    private val fileTransferDao = db.fileTransferDao()
    private val eventLogDao = db.eventLogDao()
    private val settingsDao = db.settingsDao()
    private val userSettingsDao = db.userSettingsDao()

    val identityFlow: Flow<DeviceIdentityEntity?> = identityDao.observeIdentity()
    val peersFlow: Flow<List<PeerEntity>> = peerDao.observeAllPeers()
    val connectionsFlow: Flow<List<ConnectionEntity>> = connectionDao.observeConnections()
    val allMessagesFlow: Flow<List<MessageEntity>> = messageDao.observeAllMessages()
    val groupsFlow: Flow<List<GroupEntity>> = groupDao.observeGroups()
    val emergencyBroadcastsFlow: Flow<List<EmergencyBroadcastEntity>> = emergencyDao.observeAll()
    val routesFlow: Flow<List<RouteEntity>> = routeDao.observeRoutes()
    val pendingPacketsFlow: Flow<List<PendingPacketEntity>> = pendingPacketDao.observePendingPackets()
    val transfersFlow: Flow<List<FileTransferEntity>> = fileTransferDao.observeTransfers()
    val securityEventsFlow: Flow<List<SecurityEventEntity>> = eventLogDao.observeSecurityEvents()
    val networkEventsFlow: Flow<List<NetworkEventEntity>> = eventLogDao.observeNetworkEvents()
    val settingsFlow: Flow<SettingsEntity> = settingsDao.observeSettings().map { it ?: SettingsEntity() }
    val userSettingsFlow: Flow<UserSettings> = userSettingsDao.observeUserSettings().map { it ?: UserSettings() }

    suspend fun ensureInitialized(): DeviceIdentityEntity {
        val existingSettings = settingsDao.getSettingsOnce()
        if (existingSettings == null) {
            settingsDao.upsert(SettingsEntity())
        }
        val existingUserSettings = userSettingsDao.getUserSettingsOnce()
        if (existingUserSettings == null) {
            userSettingsDao.upsert(
                UserSettings(
                    biometricLockEnabled = existingSettings?.biometricLockEnabled ?: false
                )
            )
        }
        val keys: LocalIdentityKeys = crypto.ensureLocalIdentity()
        val existing = identityDao.getIdentityOnce()
        if (existing == null || existing.deviceId != keys.deviceId) {
            val entity = DeviceIdentityEntity(
                deviceId = keys.deviceId,
                alias = keys.alias,
                signingPublicKey = keys.signingPublicKeyB64,
                agreementPublicKey = keys.agreementPublicKeyB64,
                fingerprint = keys.fingerprint
            )
            identityDao.upsert(entity)
            logSecurityEvent(
                eventType = "KEY_GEN",
                summary = "Generated Android KeyStore-wrapped EC P-256 identity (${keys.deviceId})"
            )
            return entity
        }
        return existing
    }

    suspend fun updateLocalAlias(newAlias: String) {
        val keys = crypto.updateAlias(newAlias)
        val current = identityDao.getIdentityOnce()
        identityDao.upsert(
            DeviceIdentityEntity(
                deviceId = keys.deviceId,
                alias = keys.alias,
                signingPublicKey = keys.signingPublicKeyB64,
                agreementPublicKey = keys.agreementPublicKeyB64,
                fingerprint = keys.fingerprint,
                createdAt = current?.createdAt ?: System.currentTimeMillis()
            )
        )
    }

    suspend fun updateSettings(transform: (SettingsEntity) -> SettingsEntity) {
        val current = settingsDao.getSettingsOnce() ?: SettingsEntity()
        val updated = transform(current)
        settingsDao.upsert(updated)
        // Keep UserSettings biometricLockEnabled in sync with SettingsEntity
        userSettingsDao.setBiometricLockEnabled(updated.biometricLockEnabled)
    }

    suspend fun getSettingsOnce(): SettingsEntity = settingsDao.getSettingsOnce() ?: SettingsEntity()

    suspend fun updateUserSettings(transform: (UserSettings) -> UserSettings) {
        val current = userSettingsDao.getUserSettingsOnce() ?: UserSettings()
        val updated = transform(current)
        userSettingsDao.upsert(updated)
        // Keep SettingsEntity biometricLockEnabled in sync with UserSettings
        val currentSettings = settingsDao.getSettingsOnce() ?: SettingsEntity()
        if (currentSettings.biometricLockEnabled != updated.biometricLockEnabled) {
            settingsDao.upsert(currentSettings.copy(biometricLockEnabled = updated.biometricLockEnabled))
        }
    }

    suspend fun getUserSettingsOnce(): UserSettings = userSettingsDao.getUserSettingsOnce() ?: UserSettings()

    suspend fun getPeerById(deviceId: String): PeerEntity? = peerDao.getPeerById(deviceId)

    suspend fun getConnectedPeersOnce(): List<PeerEntity> = peerDao.getConnectedPeersOnce()

    suspend fun getUnblockedPeersOnce(): List<PeerEntity> = peerDao.getUnblockedPeersOnce()

    suspend fun upsertDiscoveredPeer(
        deviceId: String,
        alias: String,
        signingPublicKey: String,
        agreementPublicKey: String,
        transport: String,
        rssiDbm: Int = -65,
        address: String? = null,
        port: Int? = null,
        connected: Boolean = false
    ): PeerEntity {
        val existing = peerDao.getPeerById(deviceId)
        val fp = if (signingPublicKey.isNotBlank() && agreementPublicKey.isNotBlank()) {
            crypto.computeFingerprint(signingPublicKey, agreementPublicKey)
        } else {
            existing?.fingerprint ?: deviceId.uppercase()
        }
        val merged = PeerEntity(
            deviceId = deviceId,
            alias = alias.ifBlank { existing?.alias ?: "Node-${deviceId.take(6)}" },
            signingPublicKey = signingPublicKey.ifBlank { existing?.signingPublicKey.orEmpty() },
            agreementPublicKey = agreementPublicKey.ifBlank { existing?.agreementPublicKey.orEmpty() },
            fingerprint = fp,
            verifiedByQr = existing?.verifiedByQr ?: false,
            blocked = existing?.blocked ?: false,
            isConnected = if (existing?.blocked == true) false else connected || (existing?.isConnected ?: false),
            activeTransport = if (connected) transport else (existing?.activeTransport ?: transport),
            signalStrengthDbm = rssiDbm,
            reputationScore = existing?.reputationScore ?: 100,
            relaySuccessCount = existing?.relaySuccessCount ?: 0,
            relayDropCount = existing?.relayDropCount ?: 0,
            ratchetCounter = existing?.ratchetCounter ?: 0,
            lastAddress = address ?: existing?.lastAddress,
            lastPort = port ?: existing?.lastPort,
            lastLat = existing?.lastLat,
            lastLon = existing?.lastLon,
            lastLocationAt = existing?.lastLocationAt,
            lastSeenAt = System.currentTimeMillis()
        )
        peerDao.upsert(merged)
        return merged
    }

    suspend fun importVerifiedPeer(payload: PeerIdentityPayload): PeerEntity {
        val existing = peerDao.getPeerById(payload.deviceId)
        val entity = PeerEntity(
            deviceId = payload.deviceId,
            alias = payload.alias,
            signingPublicKey = payload.signingPublicKeyB64,
            agreementPublicKey = payload.agreementPublicKeyB64,
            fingerprint = payload.fingerprint,
            verifiedByQr = true,
            blocked = false,
            isConnected = existing?.isConnected ?: false,
            activeTransport = existing?.activeTransport ?: "QR",
            signalStrengthDbm = existing?.signalStrengthDbm ?: -60,
            reputationScore = (existing?.reputationScore ?: 100).coerceAtLeast(100),
            relaySuccessCount = existing?.relaySuccessCount ?: 0,
            relayDropCount = existing?.relayDropCount ?: 0,
            ratchetCounter = existing?.ratchetCounter ?: 0,
            lastAddress = existing?.lastAddress,
            lastPort = existing?.lastPort,
            lastLat = existing?.lastLat,
            lastLon = existing?.lastLon,
            lastLocationAt = existing?.lastLocationAt,
            lastSeenAt = System.currentTimeMillis()
        )
        peerDao.upsert(entity)
        logSecurityEvent(
            eventType = "QR_VERIFY",
            peerId = payload.deviceId,
            summary = "Verified out-of-band identity for ${payload.alias} (${payload.fingerprint.take(14)}...)"
        )
        return entity
    }

    suspend fun markPeerConnected(
        deviceId: String,
        alias: String,
        transport: String,
        address: String,
        rssiDbm: Int = -62
    ) {
        peerDao.updateConnectionState(deviceId, true, transport, System.currentTimeMillis())
        connectionDao.closeActiveConnectionsForPeer(deviceId, "REPLACED", System.currentTimeMillis())
        connectionDao.insert(
            ConnectionEntity(
                peerId = deviceId,
                peerAlias = alias,
                transportType = transport,
                remoteAddress = address,
                rssiDbm = rssiDbm,
                status = "CONNECTED"
            )
        )
    }

    suspend fun markPeerDisconnected(deviceId: String) {
        peerDao.updateConnectionState(deviceId, false, "NONE", System.currentTimeMillis())
        connectionDao.closeActiveConnectionsForPeer(deviceId, "DISCONNECTED", System.currentTimeMillis())
    }

    suspend fun markAllDisconnected() {
        peerDao.markAllDisconnected()
    }

    suspend fun setPeerVerified(deviceId: String, verified: Boolean) {
        peerDao.setVerified(deviceId, verified)
        logSecurityEvent(
            eventType = if (verified) "QR_VERIFY" else "TRUST_REVOKE",
            peerId = deviceId,
            summary = if (verified) "Marked peer $deviceId as trusted" else "Revoked trust for peer $deviceId"
        )
    }

    suspend fun setPeerBlocked(deviceId: String, blocked: Boolean) {
        peerDao.setBlocked(deviceId, blocked)
        if (blocked) {
            connectionDao.closeActiveConnectionsForPeer(deviceId, "BLOCKED", System.currentTimeMillis())
        }
        logSecurityEvent(
            eventType = if (blocked) "PEER_BLOCKED" else "PEER_UNBLOCKED",
            peerId = deviceId,
            severity = if (blocked) "WARN" else "INFO",
            summary = if (blocked) "Blocked and revoked peer $deviceId" else "Unblocked peer $deviceId"
        )
    }

    suspend fun updatePeerLocation(deviceId: String, lat: Double, lon: Double, timestamp: Long) {
        val peer = peerDao.getPeerById(deviceId) ?: return
        peerDao.upsert(peer.copy(lastLat = lat, lastLon = lon, lastLocationAt = timestamp, lastSeenAt = timestamp))
    }

    suspend fun recordRelayOutcome(deviceId: String, success: Boolean) {
        val peer = peerDao.getPeerById(deviceId) ?: return
        val newScore = if (success) (peer.reputationScore + 2).coerceAtMost(200)
        else (peer.reputationScore - 5).coerceAtLeast(0)
        peerDao.upsert(
            peer.copy(
                reputationScore = newScore,
                relaySuccessCount = peer.relaySuccessCount + if (success) 1 else 0,
                relayDropCount = peer.relayDropCount + if (!success) 1 else 0
            )
        )
    }

    fun observeConversation(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.observeConversation(conversationId)

    suspend fun saveMessage(message: MessageEntity) {
        messageDao.upsert(message)
    }

    suspend fun updateMessageStatus(messageId: String, status: String, deliveredAt: Long?, hopCount: Int) {
        messageDao.updateDeliveryStatus(messageId, status, deliveredAt, hopCount)
    }

    suspend fun toggleMessageReaction(message: MessageEntity, emoji: String) {
        val current = message.reactionsCsv.split(",").filter { it.isNotBlank() }.toMutableList()
        if (current.contains(emoji)) current.remove(emoji) else current.add(emoji)
        messageDao.updateReactions(message.messageId, current.joinToString(","))
    }

    suspend fun createGroup(
        name: String,
        description: String,
        category: String = "COMMUNITY",
        memberPeers: List<PeerEntity>
    ): GroupEntity {
        val local = ensureInitialized()
        val groupId = "grp_${UUID.randomUUID().toString().take(8)}"
        val inviteCode = "ML-${groupId.takeLast(6).uppercase()}"
        val group = GroupEntity(
            groupId = groupId,
            name = name.trim().ifEmpty { "Local Mesh Group" },
            description = description.trim(),
            category = category,
            inviteCode = inviteCode,
            groupSymmetricKeyB64 = crypto.generateGroupKeyB64(),
            createdBy = local.deviceId
        )
        groupDao.upsertGroup(group)
        groupDao.upsertMember(
            GroupMemberEntity(
                groupId = group.groupId,
                peerId = local.deviceId,
                peerAlias = local.alias,
                role = "ADMIN"
            )
        )
        memberPeers.forEach { peer ->
            groupDao.upsertMember(
                GroupMemberEntity(
                    groupId = group.groupId,
                    peerId = peer.deviceId,
                    peerAlias = peer.alias,
                    role = "MEMBER"
                )
            )
        }
        logSecurityEvent(
            eventType = "GROUP_KEY_GEN",
            summary = "Created $category group '${group.name}' with ${memberPeers.size + 1} members"
        )
        return group
    }

    suspend fun joinGroupFromInvite(invite: MeshCryptoEngine.GroupInvitePayload): GroupEntity {
        val local = ensureInitialized()
        val group = GroupEntity(
            groupId = invite.groupId,
            name = invite.name,
            description = invite.description,
            category = invite.category,
            inviteCode = "ML-${invite.groupId.takeLast(6).uppercase()}",
            groupSymmetricKeyB64 = invite.groupKeyB64,
            keyEpoch = invite.epoch,
            createdBy = invite.createdBy
        )
        groupDao.upsertGroup(group)
        groupDao.upsertMember(
            GroupMemberEntity(
                groupId = group.groupId,
                peerId = local.deviceId,
                peerAlias = local.alias,
                role = "MEMBER"
            )
        )
        logSecurityEvent(
            eventType = "GROUP_JOIN",
            summary = "Joined ${invite.category} group '${invite.name}' (epoch ${invite.epoch})"
        )
        return group
    }

    suspend fun addGroupMember(groupId: String, peer: PeerEntity) {
        groupDao.upsertMember(
            GroupMemberEntity(
                groupId = groupId,
                peerId = peer.deviceId,
                peerAlias = peer.alias,
                role = "MEMBER"
            )
        )
    }

    suspend fun removeGroupMember(groupId: String, peerId: String) {
        groupDao.removeMember(groupId, peerId)
    }

    suspend fun updateGroupMemberRole(groupId: String, peerId: String, role: String) {
        groupDao.updateMemberRole(groupId, peerId, role)
    }

    suspend fun rotateGroupKey(groupId: String) {
        val existing = groupDao.getGroupById(groupId) ?: return
        groupDao.upsertGroup(
            existing.copy(
                groupSymmetricKeyB64 = crypto.generateGroupKeyB64(),
                keyEpoch = existing.keyEpoch + 1
            )
        )
        logSecurityEvent(
            eventType = "GROUP_KEY_ROTATE",
            summary = "Rotated AES-256 group key for '${existing.name}' to epoch ${existing.keyEpoch + 1}"
        )
    }

    suspend fun getGroupById(groupId: String): GroupEntity? = groupDao.getGroupById(groupId)

    fun observeGroupMembers(groupId: String): Flow<List<GroupMemberEntity>> = groupDao.observeMembers(groupId)

    suspend fun saveEmergencyBroadcast(item: EmergencyBroadcastEntity) {
        emergencyDao.upsert(item)
    }

    suspend fun recordEmergencyAck(messageId: String, ackPeerId: String, ackAlias: String) {
        val existing = emergencyDao.getById(messageId) ?: return
        val entries = existing.ackedByCsv.split(",").filter { it.isNotBlank() }.toMutableList()
        val tag = "$ackPeerId:$ackAlias"
        if (entries.none { it.startsWith("$ackPeerId:") }) {
            entries.add(tag)
            emergencyDao.upsert(
                existing.copy(
                    ackedByCsv = entries.joinToString(","),
                    ackCount = entries.size
                )
            )
        }
    }

    suspend fun incrementBridgeForwardedCount() {
        updateSettings { it.copy(bridgePacketsForwarded = it.bridgePacketsForwarded + 1) }
    }

    suspend fun upsertRoute(route: RouteEntity) {
        routeDao.upsertRoute(route)
    }

    suspend fun getBestRoute(destinationId: String): RouteEntity? = routeDao.getBestRoute(destinationId)

    suspend fun enqueuePendingPacket(packet: PendingPacketEntity) {
        pendingPacketDao.upsert(packet)
    }

    suspend fun getActivePendingPackets(): List<PendingPacketEntity> =
        pendingPacketDao.getActivePendingOnce(System.currentTimeMillis())

    suspend fun removePendingPacket(messageId: String) {
        pendingPacketDao.deleteById(messageId)
    }

    suspend fun purgeExpiredCache(): Int {
        val now = System.currentTimeMillis()
        val expiredPackets = pendingPacketDao.purgeExpired(now)
        val expiredMsgs = messageDao.deleteExpired(now)
        routeDao.pruneStaleRoutes(now - 6L * 60L * 60L * 1000L)
        return expiredPackets + expiredMsgs
    }

    suspend fun upsertFileTransfer(transfer: FileTransferEntity) {
        fileTransferDao.upsertTransfer(transfer)
    }

    suspend fun getFileTransferById(transferId: String): FileTransferEntity? =
        fileTransferDao.getTransferById(transferId)

    suspend fun insertFileChunks(chunks: List<FileChunkEntity>) {
        fileTransferDao.upsertChunks(chunks)
    }

    suspend fun getIncompleteChunks(transferId: String): List<FileChunkEntity> =
        fileTransferDao.getIncompleteChunks(transferId)

    suspend fun markChunkCompleted(transferId: String, chunkIndex: Int) {
        fileTransferDao.markChunkCompleted(transferId, chunkIndex)
    }

    suspend fun logSecurityEvent(
        eventType: String,
        peerId: String? = null,
        severity: String = "INFO",
        summary: String
    ) {
        eventLogDao.insertSecurityEvent(
            SecurityEventEntity(
                eventType = eventType,
                peerId = peerId,
                severity = severity,
                summary = summary
            )
        )
    }

    suspend fun logNetworkEvent(
        transportType: String,
        eventType: String,
        peerId: String? = null,
        summary: String
    ) {
        eventLogDao.insertNetworkEvent(
            NetworkEventEntity(
                transportType = transportType,
                eventType = eventType,
                peerId = peerId,
                summary = summary
            )
        )
    }

    suspend fun clearNetworkEvents() {
        eventLogDao.clearNetworkEvents()
    }
}

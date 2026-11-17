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
import com.example.data.entities.GroupKeyUpdateEntity
import com.example.data.entities.GroupRoleUpdateEntity
import com.example.data.entities.MessageEntity
import com.example.data.entities.NetworkEventEntity
import com.example.data.entities.PeerEntity
import com.example.data.entities.PendingPacketEntity
import com.example.data.entities.RouteEntity
import com.example.data.entities.SecurityEventEntity
import com.example.data.entities.SettingsEntity
import com.example.data.entities.UserSettings
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class PeerIdentityKeyChangedException(
    val peerId: String,
    val previousFingerprint: String,
    val receivedFingerprint: String
) : SecurityException("Peer identity key changed")

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
        if (existing != null &&
            (existing.fingerprint != payload.fingerprint ||
                existing.signingPublicKey != payload.signingPublicKeyB64 ||
                existing.agreementPublicKey != payload.agreementPublicKeyB64)
        ) {
            logSecurityEvent(
                eventType = "PEER_IDENTITY_KEY_CHANGED",
                peerId = payload.deviceId,
                severity = "CRITICAL",
                summary = "Rejected QR identity update because stored and received public keys differ"
            )
            throw PeerIdentityKeyChangedException(
                payload.deviceId, existing.fingerprint, payload.fingerprint
            )
        }
        require(crypto.areValidIdentityPublicKeys(
            payload.signingPublicKeyB64,
            payload.agreementPublicKeyB64
        )) { "Peer identity contains invalid EC public keys" }
        val computedFingerprint = crypto.computeFingerprint(
            payload.signingPublicKeyB64,
            payload.agreementPublicKeyB64
        )
        val expectedDeviceId = computedFingerprint.replace(":", "").take(16).lowercase()
        require(payload.fingerprint == computedFingerprint && payload.deviceId == expectedDeviceId) {
            "Peer identity fingerprint or device ID does not match its public keys"
        }
        val entity = PeerEntity(
            deviceId = payload.deviceId,
            alias = payload.alias,
            signingPublicKey = payload.signingPublicKeyB64,
            agreementPublicKey = payload.agreementPublicKeyB64,
            fingerprint = payload.fingerprint,
            verifiedByQr = existing?.verifiedByQr ?: false,
            blocked = existing?.blocked ?: false,
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
            eventType = "QR_IDENTITY_IMPORTED",
            peerId = payload.deviceId,
            summary = "Imported identity QR; compare its fingerprint out-of-band before marking trusted"
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

    suspend fun getPendingSessionMessages(peerId: String): List<MessageEntity> =
        messageDao.getPendingSessionMessages(peerId, System.currentTimeMillis())

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
        logSecurityEvent(
            eventType = "GROUP_KEY_GEN",
            summary = "Created $category group '${group.name}' with ${memberPeers.size} invitation targets"
        )
        return group
    }

    suspend fun joinGroupFromInvite(invite: MeshCryptoEngine.GroupInvitePayload): GroupEntity {
        require(invite.groupId.matches(Regex("[A-Za-z0-9_-]{4,80}"))) { "Invalid group identifier" }
        require(invite.createdBy.isNotBlank()) { "Missing group creator identity" }
        require(invite.epoch > 0) { "Invalid group key epoch" }
        require(crypto.isValidGroupKeyB64(invite.groupKeyB64)) { "Invalid group encryption key" }
        val existing = groupDao.getGroupById(invite.groupId)
        if (existing != null) {
            require(existing.createdBy == invite.createdBy) { "Group creator identity mismatch" }
            require(invite.epoch >= existing.keyEpoch) { "Stale group invitation epoch" }
            require(invite.epoch != existing.keyEpoch || invite.groupKeyB64 == existing.groupSymmetricKeyB64) {
                "Conflicting group key for the same epoch"
            }
        }
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
                peerId = invite.createdBy,
                peerAlias = peerDao.getPeerById(invite.createdBy)?.alias ?: invite.createdBy,
                role = "ADMIN"
            )
        )
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
        val local = ensureInitialized()
        require(groupDao.getMember(groupId, local.deviceId)?.role == "ADMIN") {
            "Only a group admin can add members"
        }
        require(peer.verifiedByQr && !peer.blocked) { "Peer must be trusted and unblocked before invitation" }
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
        val local = ensureInitialized()
        require(groupDao.getMember(groupId, local.deviceId)?.role == "ADMIN") {
            "Only a group admin can remove members"
        }
        require(peerId != local.deviceId) { "The local admin cannot remove itself" }
        groupDao.removeMember(groupId, peerId)
    }

    suspend fun updateGroupMemberRole(groupId: String, peerId: String, role: String, issuedAt: Long = System.currentTimeMillis()) {
        val local = ensureInitialized()
        require(groupDao.getMember(groupId, local.deviceId)?.role == "ADMIN") {
            "Only a group admin can update member roles"
        }
        require(role == "ADMIN" || role == "MEMBER") { "Invalid group role" }
        require(peerId != local.deviceId || role == "ADMIN") { "The local admin cannot demote itself" }
        val target = groupDao.getMember(groupId, peerId) ?: throw IllegalArgumentException("Group member does not exist")
        if (target.role == "ADMIN" && role != "ADMIN") {
            require(groupDao.countAdmins(groupId) > 1) { "Cannot demote the last group admin" }
        }
        groupDao.updateMemberRole(groupId, peerId, role, issuedAt)
        logSecurityEvent(
            eventType = "GROUP_ROLE_CHANGED_LOCAL",
            peerId = peerId,
            summary = "Updated local group role; routing layer distributes the signed change"
        )
    }

    suspend fun rotateGroupKey(groupId: String) {
        val local = ensureInitialized()
        require(groupDao.getMember(groupId, local.deviceId)?.role == "ADMIN") {
            "Only a group admin can rotate the group key"
        }
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

    suspend fun isGroupAdmin(groupId: String, peerId: String): Boolean =
        groupDao.getMember(groupId, peerId)?.role == "ADMIN"

    suspend fun rotateGroupKeyForDistribution(
        groupId: String
    ): Pair<GroupEntity, List<GroupMemberEntity>> = db.withTransaction {
        val local = ensureInitialized()
        require(groupDao.getMember(groupId, local.deviceId)?.role == "ADMIN") {
            "Only a group admin can rotate the group key"
        }
        val existing = groupDao.getGroupById(groupId)
            ?: throw IllegalArgumentException("Group does not exist")
        val updated = existing.copy(
            groupSymmetricKeyB64 = crypto.generateGroupKeyB64(),
            keyEpoch = existing.keyEpoch + 1
        )
        groupDao.upsertGroup(updated)
        logSecurityEvent(
            eventType = "GROUP_KEY_ROTATE",
            summary = "Created group key epoch ${updated.keyEpoch} for authenticated distribution"
        )
        updated to groupDao.getMembersOnce(groupId)
    }

    suspend fun removeGroupMemberAndRotate(
        groupId: String,
        peerId: String
    ): Pair<GroupEntity, List<GroupMemberEntity>> = db.withTransaction {
        val local = ensureInitialized()
        require(groupDao.getMember(groupId, local.deviceId)?.role == "ADMIN") {
            "Only a group admin can remove members"
        }
        require(peerId != local.deviceId) { "The local admin cannot remove itself" }
        val existing = groupDao.getGroupById(groupId)
            ?: throw IllegalArgumentException("Group does not exist")
        require(groupDao.getMember(groupId, peerId) != null) { "Member is not in this group" }
        groupDao.removeMember(groupId, peerId)
        val updated = existing.copy(
            groupSymmetricKeyB64 = crypto.generateGroupKeyB64(),
            keyEpoch = existing.keyEpoch + 1
        )
        groupDao.upsertGroup(updated)
        logSecurityEvent(
            eventType = "GROUP_MEMBER_REMOVED_KEY_ROTATED",
            peerId = peerId,
            severity = "WARN",
            summary = "Removed member locally and created epoch ${updated.keyEpoch}; distribute to remaining members"
        )
        updated to groupDao.getMembersOnce(groupId)
    }

    suspend fun applyGroupKeyUpdate(
        groupId: String,
        epoch: Int,
        groupKeyB64: String,
        senderId: String,
        revokedPeerId: String?
    ): Boolean = db.withTransaction {
        val existing = groupDao.getGroupById(groupId) ?: return@withTransaction false
        if (groupDao.getMember(groupId, senderId)?.role != "ADMIN") return@withTransaction false
        if (epoch <= existing.keyEpoch || !crypto.isValidGroupKeyB64(groupKeyB64)) {
            return@withTransaction false
        }
        groupDao.upsertGroup(
            existing.copy(groupSymmetricKeyB64 = groupKeyB64, keyEpoch = epoch)
        )
        if (!revokedPeerId.isNullOrBlank() && revokedPeerId != senderId) {
            groupDao.removeMember(groupId, revokedPeerId)
        }
        logSecurityEvent(
            eventType = "GROUP_KEY_UPDATE_APPLIED",
            peerId = senderId,
            summary = "Applied authenticated group key epoch $epoch"
        )
        true
    }

    suspend fun recordGroupKeyUpdate(
        groupId: String, epoch: Int, peerId: String, packetId: String, status: String,
        revokedPeerId: String? = null
    ) {
        groupDao.upsertKeyUpdate(
            GroupKeyUpdateEntity(packetId, groupId, epoch, peerId, status, revokedPeerId = revokedPeerId)
        )
    }

    suspend fun markGroupKeyUpdateAcknowledged(packetId: String, acknowledgingPeerId: String): Boolean {
        val updateRecord = groupDao.getKeyUpdateByPacketId(packetId) ?: return false
        if (updateRecord.peerId != acknowledgingPeerId) {
            logSecurityEvent(
                eventType = "GROUP_KEY_ACK_SPOOF_REJECTED",
                peerId = acknowledgingPeerId,
                severity = "WARN",
                summary = "Ignored a group key acknowledgement from a peer other than the intended recipient"
            )
            return false
        }
        val now = System.currentTimeMillis()
        groupDao.updateKeyUpdateStatus(packetId, "ACKED", now, now)
        logSecurityEvent(
            eventType = "GROUP_KEY_UPDATE_ACKED",
            peerId = updateRecord.peerId,
            summary = "Intended group member acknowledged the key update"
        )
        return true
    }

    suspend fun markGroupKeyUpdateSuperseded(packetId: String) {
        groupDao.updateKeyUpdateStatus(packetId, "SUPERSEDED", System.currentTimeMillis(), null)
    }

    suspend fun supersedeOlderGroupKeyUpdates(groupId: String, epoch: Int) {
        groupDao.supersedeOlderKeyUpdates(groupId, epoch, System.currentTimeMillis())
    }

    suspend fun getUnresolvedGroupKeyUpdates(groupId: String): List<GroupKeyUpdateEntity> =
        groupDao.getUnresolvedKeyUpdates(groupId)

    suspend fun recordGroupRoleUpdate(update: GroupRoleUpdateEntity) {
        groupDao.upsertRoleUpdate(update)
    }

    suspend fun markGroupRoleUpdateAcknowledged(packetId: String, acknowledgingPeerId: String): Boolean {
        val record = groupDao.getRoleUpdateByPacketId(packetId) ?: return false
        if (record.peerId != acknowledgingPeerId) {
            logSecurityEvent(
                eventType = "GROUP_ROLE_ACK_SPOOF_REJECTED",
                peerId = acknowledgingPeerId,
                severity = "WARN",
                summary = "Ignored a group role acknowledgement from a peer other than the intended recipient"
            )
            return false
        }
        val now = System.currentTimeMillis()
        groupDao.updateRoleUpdateStatus(packetId, "ACKED", now, now)
        logSecurityEvent(
            eventType = "GROUP_ROLE_UPDATE_ACKED",
            peerId = record.peerId,
            summary = "Intended group member acknowledged the role update"
        )
        return true
    }

    suspend fun markGroupRoleUpdateSuperseded(packetId: String) {
        groupDao.updateRoleUpdateStatus(packetId, "SUPERSEDED", System.currentTimeMillis(), null)
    }

    suspend fun supersedeOlderGroupRoleUpdates(groupId: String, epoch: Int) {
        groupDao.supersedeOlderRoleUpdates(groupId, epoch, System.currentTimeMillis())
    }

    suspend fun supersedeOlderGroupRoleUpdatesForTarget(groupId: String, targetPeerId: String, issuedAt: Long) {
        groupDao.supersedeOlderRoleUpdatesForTarget(
            groupId, targetPeerId, issuedAt, System.currentTimeMillis()
        )
    }

    suspend fun getUnresolvedGroupRoleUpdates(groupId: String): List<GroupRoleUpdateEntity> =
        groupDao.getUnresolvedRoleUpdates(groupId)

    suspend fun getUnresolvedGroupRoleUpdatesForPeer(peerId: String): List<GroupRoleUpdateEntity> =
        groupDao.getUnresolvedRoleUpdatesForPeer(peerId)

    suspend fun hasUnacknowledgedGroupKeyUpdates(groupId: String): Boolean =
        groupDao.countUnacknowledgedKeyUpdates(groupId) > 0 ||
            groupDao.countUnacknowledgedRoleUpdates(groupId) > 0

    suspend fun getGroupMembersOnce(groupId: String): List<GroupMemberEntity> = groupDao.getMembersOnce(groupId)

    suspend fun applyGroupMemberRoleUpdate(
        groupId: String, targetPeerId: String, role: String, senderId: String, epoch: Int, issuedAt: Long
    ): Boolean = db.withTransaction {
        if (role !in setOf("ADMIN", "MEMBER")) return@withTransaction false
        val group = groupDao.getGroupById(groupId) ?: return@withTransaction false
        if (group.keyEpoch != epoch || groupDao.getMember(groupId, senderId)?.role != "ADMIN") return@withTransaction false
        val target = groupDao.getMember(groupId, targetPeerId) ?: return@withTransaction false
        if (issuedAt < target.roleUpdatedAt) return@withTransaction false
        // An exact duplicate is idempotently accepted so the receiver can ACK a retry
        // when the original delivery acknowledgement was lost.
        if (issuedAt == target.roleUpdatedAt) return@withTransaction target.role == role
        if (target.role == "ADMIN" && role == "MEMBER" && groupDao.countAdmins(groupId) <= 1) return@withTransaction false
        groupDao.updateMemberRole(groupId, targetPeerId, role, issuedAt)
        logSecurityEvent(eventType = "GROUP_ROLE_UPDATE_APPLIED", peerId = senderId, summary = "Applied authenticated group member role update")
        true
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

    suspend fun getPendingPacketById(messageId: String): PendingPacketEntity? =
        pendingPacketDao.getById(messageId)

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

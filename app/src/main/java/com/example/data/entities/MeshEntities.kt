package com.example.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerId: String,
    val peerAlias: String,
    val transportType: String,
    val remoteAddress: String,
    val rssiDbm: Int = -65,
    val latencyMs: Long = 0L,
    val status: String, // CONNECTED, DISCONNECTED, HANDSHAKING
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String, // peerId or groupId
    val senderId: String,
    val senderAlias: String,
    val destinationId: String,
    val isGroup: Boolean = false,
    val isOutgoing: Boolean,
    val messageKind: String = "TEXT", // TEXT, VOICE, LOCATION, FILE_NOTICE, EMERGENCY
    val body: String,
    val attachmentPath: String? = null,
    val reactionsCsv: String = "",
    val deliveryStatus: String = "QUEUED", // QUEUED, SPRAYED, RELAYED, DELIVERED, FAILED, EXPIRED
    val routingStrategy: String = "STORE_AND_FORWARD",
    val hopCount: Int = 0,
    val expiresAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val deliveredAt: Long? = null
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val description: String = "",
    val category: String = "COMMUNITY", // COMMUNITY, EVENT, NEIGHBORHOOD, RESCUE
    val inviteCode: String = "",
    val groupSymmetricKeyB64: String,
    val keyEpoch: Int = 1,
    val createdBy: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "group_members", primaryKeys = ["groupId", "peerId"])
data class GroupMemberEntity(
    val groupId: String,
    val peerId: String,
    val peerAlias: String,
    val role: String = "MEMBER", // ADMIN, MEMBER
    val joinedAt: Long = System.currentTimeMillis(),
    val roleUpdatedAt: Long = 0L
)

@Entity(tableName = "group_key_updates", indices = [androidx.room.Index(value = ["groupId"])])
data class GroupKeyUpdateEntity(
    @androidx.room.PrimaryKey val packetId: String,
    val groupId: String,
    val epoch: Int,
    val peerId: String,
    val status: String = "PENDING",
    val updatedAt: Long = System.currentTimeMillis(),
    val acknowledgedAt: Long? = null
)

@Entity(tableName = "emergency_broadcasts")
data class EmergencyBroadcastEntity(
    @PrimaryKey val messageId: String,
    val senderId: String,
    val senderAlias: String,
    val body: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isOutgoing: Boolean,
    val ackedByCsv: String = "",
    val ackCount: Int = 0,
    val relayCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "routes", primaryKeys = ["destinationId", "nextHopId"])
data class RouteEntity(
    val destinationId: String,
    val nextHopId: String,
    val transportType: String,
    val hopCount: Int,
    val qualityScore: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "pending_packets")
data class PendingPacketEntity(
    @PrimaryKey val messageId: String,
    val destinationId: String,
    val packetType: String,
    val routingStrategy: String,
    val rawPacketJson: String,
    val priority: Int = 1,
    val remainingSprayCopies: Int = 4,
    val hopLimit: Int = 6,
    val attemptCount: Int = 0,
    val expiresAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAttemptAt: Long = 0L
)

@Entity(tableName = "file_transfers")
data class FileTransferEntity(
    @PrimaryKey val transferId: String,
    val peerId: String,
    val peerAlias: String,
    val fileName: String,
    val mimeType: String,
    val localUriOrPath: String,
    val totalBytes: Long,
    val totalChunks: Int,
    val completedChunks: Int = 0,
    val sha256: String,
    val isOutgoing: Boolean,
    val transportType: String = "LOCAL_LAN",
    val status: String = "QUEUED", // QUEUED, TRANSFERRING, PAUSED, COMPLETED, VERIFIED, FAILED
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "file_chunks", primaryKeys = ["transferId", "chunkIndex"])
data class FileChunkEntity(
    val transferId: String,
    val chunkIndex: Int,
    val byteOffset: Long,
    val byteLength: Int,
    val chunkSha256: String,
    val completed: Boolean = false
)

@Entity(tableName = "security_events")
data class SecurityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventType: String, // KEY_GEN, QR_VERIFY, SIG_FAIL, REPLAY_DROP, PEER_BLOCKED, ENCRYPT_OK
    val peerId: String? = null,
    val severity: String = "INFO", // INFO, WARN, ALERT
    val summary: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "network_events")
data class NetworkEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transportType: String,
    val eventType: String, // DISCOVERY, CONNECT, RELAY_FWD, ACK_RX, ROUTE_UPDATE, LOOPBACK_TEST, BRIDGE_FWD
    val peerId: String? = null,
    val summary: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val onboardingCompleted: Boolean = false,
    val themeMode: String = "DARK", // DARK, LIGHT, SYSTEM
    val defaultRoutingStrategy: String = "AUTONOMOUS",
    val defaultHopLimit: Int = 6,
    val defaultMessageTtlHours: Int = 24,
    val sprayCopies: Int = 4,
    val maxRelayPerMinute: Int = 60,
    val backgroundRelayEnabled: Boolean = true,
    val bleEnabled: Boolean = true,
    val bluetoothClassicEnabled: Boolean = false,
    val wifiDirectEnabled: Boolean = true,
    val wifiAwareEnabled: Boolean = true,
    val localLanEnabled: Boolean = true,
    val autoAcceptHandshakes: Boolean = true,
    val biometricLockEnabled: Boolean = false,
    val emergencyModeActive: Boolean = false,
    val emergencyMessage: String = "MeshLink Emergency Beacon: Need assistance at my last broadcast coordinates.",
    val bridgeEnabled: Boolean = false,
    val bridgeNetworkA: String = "BLE",
    val bridgeNetworkB: String = "LOCAL_LAN",
    val bridgeAllowBroadcasts: Boolean = true,
    val bridgeRequireVerifiedPeers: Boolean = false,
    val bridgePacketsForwarded: Int = 0
)

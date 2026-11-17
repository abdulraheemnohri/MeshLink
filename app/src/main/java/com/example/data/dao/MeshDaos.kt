package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.entities.ConnectionEntity
import com.example.data.entities.EmergencyBroadcastEntity
import com.example.data.entities.FileChunkEntity
import com.example.data.entities.FileTransferEntity
import com.example.data.entities.GroupEntity
import com.example.data.entities.GroupMemberEntity
import com.example.data.entities.GroupKeyUpdateEntity
import com.example.data.entities.GroupRoleUpdateEntity
import com.example.data.entities.MessageEntity
import com.example.data.entities.NetworkEventEntity
import com.example.data.entities.PendingPacketEntity
import com.example.data.entities.RouteEntity
import com.example.data.entities.SecurityEventEntity
import com.example.data.entities.SettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connections ORDER BY startedAt DESC LIMIT 100")
    fun observeConnections(): Flow<List<ConnectionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(connection: ConnectionEntity): Long

    @Query("UPDATE connections SET status = :status, endedAt = :endedAt WHERE peerId = :peerId AND status = 'CONNECTED'")
    suspend fun closeActiveConnectionsForPeer(peerId: String, status: String, endedAt: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY createdAt DESC")
    fun observeAllMessages(): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversationId = :peerId AND isOutgoing = 1 AND deliveryStatus = 'WAITING_SESSION' AND expiresAt > :now ORDER BY createdAt ASC")
    suspend fun getPendingSessionMessages(peerId: String, now: Long): List<MessageEntity>

    @Query("UPDATE messages SET deliveryStatus = :status, deliveredAt = :deliveredAt, hopCount = :hopCount WHERE messageId = :messageId")
    suspend fun updateDeliveryStatus(messageId: String, status: String, deliveredAt: Long?, hopCount: Int)

    @Query("UPDATE messages SET reactionsCsv = :reactionsCsv WHERE messageId = :messageId")
    suspend fun updateReactions(messageId: String, reactionsCsv: String)

    @Query("DELETE FROM messages WHERE expiresAt < :now")
    suspend fun deleteExpired(now: Long): Int
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE groupId = :groupId LIMIT 1")
    suspend fun getGroupById(groupId: String): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: GroupEntity)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getMembersOnce(groupId: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND peerId = :peerId LIMIT 1")
    suspend fun getMember(groupId: String, peerId: String): GroupMemberEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: GroupMemberEntity)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND peerId = :peerId")
    suspend fun removeMember(groupId: String, peerId: String)

    @Query("UPDATE group_members SET role = :role, roleUpdatedAt = :updatedAt WHERE groupId = :groupId AND peerId = :peerId")
    suspend fun updateMemberRole(groupId: String, peerId: String, role: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId AND role = 'ADMIN'")
    suspend fun countAdmins(groupId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKeyUpdate(update: GroupKeyUpdateEntity)

    @Query("SELECT * FROM group_key_updates WHERE packetId = :packetId LIMIT 1")
    suspend fun getKeyUpdateByPacketId(packetId: String): GroupKeyUpdateEntity?

    @Query("UPDATE group_key_updates SET status = :status, updatedAt = :now, acknowledgedAt = :acknowledgedAt WHERE packetId = :packetId")
    suspend fun updateKeyUpdateStatus(packetId: String, status: String, now: Long, acknowledgedAt: Long?)

    @Query("SELECT * FROM group_key_updates WHERE groupId = :groupId AND status NOT IN ('ACKED', 'SUPERSEDED') ORDER BY updatedAt ASC")
    suspend fun getUnresolvedKeyUpdates(groupId: String): List<GroupKeyUpdateEntity>

    @Query("UPDATE group_key_updates SET status = 'SUPERSEDED', updatedAt = :now WHERE groupId = :groupId AND epoch < :epoch AND status NOT IN ('ACKED', 'SUPERSEDED')")
    suspend fun supersedeOlderKeyUpdates(groupId: String, epoch: Int, now: Long)

    @Query("SELECT COUNT(*) FROM group_key_updates WHERE groupId = :groupId AND status NOT IN ('ACKED', 'SUPERSEDED')")
    suspend fun countUnacknowledgedKeyUpdates(groupId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoleUpdate(update: GroupRoleUpdateEntity)

    @Query("SELECT * FROM group_role_updates WHERE packetId = :packetId LIMIT 1")
    suspend fun getRoleUpdateByPacketId(packetId: String): GroupRoleUpdateEntity?

    @Query("UPDATE group_role_updates SET status = :status, updatedAt = :now, acknowledgedAt = :acknowledgedAt WHERE packetId = :packetId")
    suspend fun updateRoleUpdateStatus(packetId: String, status: String, now: Long, acknowledgedAt: Long?)

    @Query("SELECT * FROM group_role_updates WHERE groupId = :groupId AND status NOT IN ('ACKED', 'SUPERSEDED') ORDER BY updatedAt ASC")
    suspend fun getUnresolvedRoleUpdates(groupId: String): List<GroupRoleUpdateEntity>

    @Query("SELECT * FROM group_role_updates WHERE peerId = :peerId AND status NOT IN ('ACKED', 'SUPERSEDED') ORDER BY updatedAt ASC")
    suspend fun getUnresolvedRoleUpdatesForPeer(peerId: String): List<GroupRoleUpdateEntity>

    @Query("UPDATE group_role_updates SET status = 'SUPERSEDED', updatedAt = :now WHERE groupId = :groupId AND epoch < :epoch AND status NOT IN ('ACKED', 'SUPERSEDED')")
    suspend fun supersedeOlderRoleUpdates(groupId: String, epoch: Int, now: Long)

    @Query("UPDATE group_role_updates SET status = 'SUPERSEDED', updatedAt = :now WHERE groupId = :groupId AND targetPeerId = :targetPeerId AND issuedAt < :issuedAt AND status NOT IN ('ACKED', 'SUPERSEDED')")
    suspend fun supersedeOlderRoleUpdatesForTarget(groupId: String, targetPeerId: String, issuedAt: Long, now: Long)

    @Query("SELECT COUNT(*) FROM group_role_updates WHERE groupId = :groupId AND status NOT IN ('ACKED', 'SUPERSEDED')")
    suspend fun countUnacknowledgedRoleUpdates(groupId: String): Int
}

@Dao
interface EmergencyBroadcastDao {
    @Query("SELECT * FROM emergency_broadcasts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<EmergencyBroadcastEntity>>

    @Query("SELECT * FROM emergency_broadcasts WHERE messageId = :messageId LIMIT 1")
    suspend fun getById(messageId: String): EmergencyBroadcastEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: EmergencyBroadcastEntity)
}

@Dao
interface RouteDao {
    @Query("SELECT * FROM routes ORDER BY updatedAt DESC")
    fun observeRoutes(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE destinationId = :destinationId ORDER BY hopCount ASC, qualityScore DESC LIMIT 1")
    suspend fun getBestRoute(destinationId: String): RouteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoute(route: RouteEntity)

    @Query("DELETE FROM routes WHERE updatedAt < :staleBefore")
    suspend fun pruneStaleRoutes(staleBefore: Long)
}

@Dao
interface PendingPacketDao {
    @Query("SELECT * FROM pending_packets ORDER BY priority DESC, createdAt ASC")
    fun observePendingPackets(): Flow<List<PendingPacketEntity>>

    @Query("SELECT * FROM pending_packets WHERE expiresAt > :now ORDER BY priority DESC, createdAt ASC")
    suspend fun getActivePendingOnce(now: Long): List<PendingPacketEntity>

    @Query("SELECT * FROM pending_packets WHERE messageId = :messageId LIMIT 1")
    suspend fun getById(messageId: String): PendingPacketEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(packet: PendingPacketEntity)

    @Query("DELETE FROM pending_packets WHERE messageId = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM pending_packets WHERE expiresAt <= :now")
    suspend fun purgeExpired(now: Long): Int
}

@Dao
interface FileTransferDao {
    @Query("SELECT * FROM file_transfers ORDER BY updatedAt DESC")
    fun observeTransfers(): Flow<List<FileTransferEntity>>

    @Query("SELECT * FROM file_transfers WHERE transferId = :transferId LIMIT 1")
    suspend fun getTransferById(transferId: String): FileTransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransfer(transfer: FileTransferEntity)

    @Query("SELECT * FROM file_chunks WHERE transferId = :transferId ORDER BY chunkIndex ASC")
    fun observeChunks(transferId: String): Flow<List<FileChunkEntity>>

    @Query("SELECT * FROM file_chunks WHERE transferId = :transferId AND completed = 0 ORDER BY chunkIndex ASC")
    suspend fun getIncompleteChunks(transferId: String): List<FileChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunks(chunks: List<FileChunkEntity>)

    @Query("UPDATE file_chunks SET completed = 1 WHERE transferId = :transferId AND chunkIndex = :chunkIndex")
    suspend fun markChunkCompleted(transferId: String, chunkIndex: Int)
}

@Dao
interface EventLogDao {
    @Query("SELECT * FROM security_events ORDER BY timestamp DESC LIMIT 150")
    fun observeSecurityEvents(): Flow<List<SecurityEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSecurityEvent(event: SecurityEventEntity)

    @Query("SELECT * FROM network_events ORDER BY timestamp DESC LIMIT 200")
    fun observeNetworkEvents(): Flow<List<NetworkEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNetworkEvent(event: NetworkEventEntity)

    @Query("DELETE FROM network_events")
    suspend fun clearNetworkEvents()
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observeSettings(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun getSettingsOnce(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: SettingsEntity)
}

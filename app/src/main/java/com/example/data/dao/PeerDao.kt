package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.entities.PeerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY isConnected DESC, lastSeenAt DESC")
    fun observeAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE blocked = 0 AND isConnected = 1 ORDER BY lastSeenAt DESC")
    fun observeConnectedPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getPeerById(deviceId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE blocked = 0 AND isConnected = 1")
    suspend fun getConnectedPeersOnce(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE blocked = 0")
    suspend fun getUnblockedPeersOnce(): List<PeerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: PeerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(peers: List<PeerEntity>)

    @Update
    suspend fun update(peer: PeerEntity)

    @Delete
    suspend fun delete(peer: PeerEntity)

    @Query("DELETE FROM peers WHERE deviceId = :deviceId")
    suspend fun deleteById(deviceId: String)

    @Query("UPDATE peers SET isConnected = :connected, activeTransport = :transport, lastSeenAt = :seenAt WHERE deviceId = :deviceId")
    suspend fun updateConnectionState(deviceId: String, connected: Boolean, transport: String, seenAt: Long)

    @Query("UPDATE peers SET verifiedByQr = :verified WHERE deviceId = :deviceId")
    suspend fun setVerified(deviceId: String, verified: Boolean)

    @Query("UPDATE peers SET blocked = :blocked, isConnected = 0 WHERE deviceId = :deviceId")
    suspend fun setBlocked(deviceId: String, blocked: Boolean)

    @Query("UPDATE peers SET isConnected = 0, activeTransport = 'NONE'")
    suspend fun markAllDisconnected()
}

package com.meshlink.data.repositories

import com.example.data.database.AppDatabase
import com.example.data.entities.PeerEntity
import kotlinx.coroutines.flow.Flow

class PeerRepository(
    private val database: AppDatabase
) {
    private val peerDao = database.peerDao()

    val allPeersFlow: Flow<List<PeerEntity>> = peerDao.observeAllPeers()
    val connectedPeersFlow: Flow<List<PeerEntity>> = peerDao.observeConnectedPeers()

    suspend fun getPeerById(deviceId: String): PeerEntity? {
        return peerDao.getPeerById(deviceId)
    }

    suspend fun upsertPeer(peer: PeerEntity) {
        peerDao.upsert(peer)
    }

    suspend fun deletePeer(peer: PeerEntity) {
        peerDao.delete(peer)
    }

    suspend fun deletePeerById(deviceId: String) {
        peerDao.deleteById(deviceId)
    }

    suspend fun updateConnectionState(
        deviceId: String,
        connected: Boolean,
        transport: String,
        seenAt: Long = System.currentTimeMillis()
    ) {
        peerDao.updateConnectionState(deviceId, connected, transport, seenAt)
    }

    suspend fun setPeerVerified(deviceId: String, verified: Boolean) {
        peerDao.setVerified(deviceId, verified)
    }

    suspend fun setPeerBlocked(deviceId: String, blocked: Boolean) {
        peerDao.setBlocked(deviceId, blocked)
    }

    suspend fun markAllDisconnected() {
        peerDao.markAllDisconnected()
    }

    suspend fun synchronizeWifiDirectPeer(
        deviceAddress: String,
        deviceName: String,
        connected: Boolean,
        rssiDbm: Int = -55
    ): PeerEntity {
        val peerId = deviceAddress.replace(":", "").lowercase()
        val alias = deviceName.ifBlank { "P2P-${peerId.take(6)}" }
        val now = System.currentTimeMillis()
        val existing = peerDao.getPeerById(peerId)

        val updatedPeer = if (existing == null) {
            PeerEntity(
                deviceId = peerId,
                alias = alias,
                signingPublicKey = "",
                agreementPublicKey = "",
                fingerprint = peerId,
                verifiedByQr = false,
                blocked = false,
                isConnected = connected,
                activeTransport = "WIFI_DIRECT",
                signalStrengthDbm = rssiDbm,
                lastAddress = deviceAddress,
                lastSeenAt = now
            )
        } else {
            existing.copy(
                alias = alias.ifBlank { existing.alias },
                isConnected = connected,
                activeTransport = "WIFI_DIRECT",
                signalStrengthDbm = rssiDbm,
                lastAddress = deviceAddress,
                lastSeenAt = now
            )
        }

        peerDao.upsert(updatedPeer)
        return updatedPeer
    }
}

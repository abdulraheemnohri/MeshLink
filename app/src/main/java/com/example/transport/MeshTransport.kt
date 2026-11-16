package com.example.transport

import com.example.core.protocol.MeshPacket
import com.example.core.protocol.TransportType
import kotlinx.coroutines.flow.Flow

data class DiscoveredEndpoint(
    val deviceId: String,
    val alias: String,
    val signingPublicKey: String,
    val agreementPublicKey: String,
    val transportType: TransportType,
    val address: String,
    val port: Int,
    val rssiDbm: Int = -60,
    val lastSeenAt: Long = System.currentTimeMillis()
)

sealed interface TransportEvent {
    data class PeerFound(val endpoint: DiscoveredEndpoint) : TransportEvent
    data class PacketReceived(
        val packet: MeshPacket,
        val transportType: TransportType,
        val remoteAddress: String
    ) : TransportEvent

    data class ConnectionChanged(
        val peerId: String,
        val transportType: TransportType,
        val connected: Boolean,
        val address: String
    ) : TransportEvent

    data class Log(
        val transportType: TransportType,
        val eventType: String,
        val message: String
    ) : TransportEvent
}

interface MeshTransport {
    val transportType: TransportType
    val events: Flow<TransportEvent>
    val isRunning: Boolean

    suspend fun start()
    suspend fun stop()
    suspend fun triggerDiscovery()
    /** Establishes an authenticated transport session without sending application data. */
    suspend fun connect(endpoint: DiscoveredEndpoint): Boolean = false

    suspend fun sendPacket(endpoint: DiscoveredEndpoint, packet: MeshPacket): Boolean
}

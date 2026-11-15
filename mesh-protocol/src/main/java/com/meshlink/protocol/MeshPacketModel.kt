package com.meshlink.protocol

import kotlinx.serialization.Serializable

@Serializable
data class MeshPacketModel(
    val protocolVersion: Int = 1,
    val messageId: String,
    val senderId: String,
    val destinationId: String,
    val packetType: String,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 86400000L,
    val hopLimit: Int = 6,
    val hopCount: Int = 0,
    val routingStrategy: String = "AUTONOMOUS",
    val encryptedPayload: String,
    val signature: String = "",
    val integrityHash: String = "",
    val visitedPeers: List<String> = emptyList(),
    val priority: Int = 1,
    val sprayCopies: Int = 4,
    val sourceCluster: String = ""
)

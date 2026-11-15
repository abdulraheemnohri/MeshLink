package com.example.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val deviceId: String,
    val alias: String,
    val signingPublicKey: String,
    val agreementPublicKey: String,
    val fingerprint: String,
    val verifiedByQr: Boolean = false,
    val blocked: Boolean = false,
    val isConnected: Boolean = false,
    val activeTransport: String = "NONE",
    val signalStrengthDbm: Int = -100,
    val reputationScore: Int = 100,
    val relaySuccessCount: Int = 0,
    val relayDropCount: Int = 0,
    val ratchetCounter: Int = 0,
    val lastAddress: String? = null,
    val lastPort: Int? = null,
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    val lastLocationAt: Long? = null,
    val lastSeenAt: Long = System.currentTimeMillis()
)

typealias Peers = PeerEntity

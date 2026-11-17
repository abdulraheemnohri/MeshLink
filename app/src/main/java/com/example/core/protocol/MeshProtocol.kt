package com.example.core.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

enum class PacketType {
    HELLO,
    PEER_DISCOVERY,
    PEER_HANDSHAKE,
    SESSION_OFFER,
    SESSION_RESPONSE,
    MESSAGE,
    GROUP_MESSAGE,
    GROUP_INVITE,
    GROUP_KEY_UPDATE,
    GROUP_MEMBER_ROLE_UPDATE,
    FILE_OFFER,
    FILE_CHUNK,
    DELIVERY_ACK,
    ROUTE_UPDATE,
    VOICE_PACKET,
    LOCATION_UPDATE,
    DISCONNECT,
    EMERGENCY_BEACON,
    BRIDGE_SYNC
}

enum class RoutingStrategy(val label: String, val summary: String) {
    DIRECT(
        label = "Direct",
        summary = "Send immediately when destination is directly reachable."
    ),
    STORE_AND_FORWARD(
        label = "Store & Forward",
        summary = "Persist in encrypted DTN queue until destination or relay reconnects."
    ),
    EPIDEMIC(
        label = "Epidemic",
        summary = "Flood to eligible unvisited peers with duplicate suppression."
    ),
    SPRAY_AND_WAIT(
        label = "Spray & Wait",
        summary = "Distribute L copies across peers, then wait for direct encounter."
    ),
    AUTONOMOUS(
        label = "Autonomous",
        summary = "Automatically pick strategy from battery, link quality, and queue load."
    )
}

enum class TransportType(val label: String, val maxPayloadBytes: Int) {
    LOCAL_LAN("Local Wi-Fi LAN", 1024 * 1024),
    WIFI_DIRECT("Wi-Fi Direct", 1024 * 1024),
    WIFI_AWARE("Wi-Fi Aware", 65536),
    HOTSPOT("Local Hotspot", 1024 * 1024),
    BLUETOOTH_CLASSIC("Bluetooth Classic", 32768),
    NEARBY_CONNECTIONS("Google Nearby Connections", 32 * 1024),
    BLE("Bluetooth LE", 512),
    NFC("NFC Tap", 4096),
    QR("QR Code", 2048)
}

data class MeshPacket(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val messageId: String = UUID.randomUUID().toString(),
    val senderId: String,
    val destinationId: String,
    val packetType: PacketType,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + DEFAULT_TTL_MS,
    val hopLimit: Int = DEFAULT_HOP_LIMIT,
    val hopCount: Int = 0,
    val routingStrategy: RoutingStrategy = RoutingStrategy.STORE_AND_FORWARD,
    val encryptedPayload: String,
    val signature: String = "",
    val integrityHash: String = "",
    val visitedPeers: List<String> = emptyList(),
    val sprayCopies: Int = 4,
    val priority: Int = PRIORITY_NORMAL,
    val sourceCluster: String = "LOCAL"
) {
    companion object {
        const val PROTOCOL_VERSION = 1
        const val DEFAULT_HOP_LIMIT = 6
        const val DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L
        const val PRIORITY_LOW = 0
        const val PRIORITY_NORMAL = 1
        const val PRIORITY_HIGH = 2
        const val PRIORITY_EMERGENCY = 3
        const val BROADCAST_ID = "MESH_BROADCAST"
    }

    fun canonicalSigningString(): String {
        return buildString {
            append(protocolVersion).append('|')
            append(messageId).append('|')
            append(senderId).append('|')
            append(destinationId).append('|')
            append(packetType.name).append('|')
            append(createdAt).append('|')
            append(expiresAt).append('|')
            append(routingStrategy.name).append('|')
            append(encryptedPayload)
        }
    }

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAt
}

object MeshPacketCodec {
    fun computeIntegrityHash(packet: MeshPacket): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(packet.canonicalSigningString().toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun encode(packet: MeshPacket): String {
        val obj = JSONObject().apply {
            put("v", packet.protocolVersion)
            put("id", packet.messageId)
            put("src", packet.senderId)
            put("dst", packet.destinationId)
            put("type", packet.packetType.name)
            put("ts", packet.createdAt)
            put("exp", packet.expiresAt)
            put("ttl", packet.hopLimit)
            put("hops", packet.hopCount)
            put("strat", packet.routingStrategy.name)
            put("pay", packet.encryptedPayload)
            put("sig", packet.signature)
            put("hash", packet.integrityHash)
            put("spray", packet.sprayCopies)
            put("prio", packet.priority)
            put("clus", packet.sourceCluster)
            put("vis", JSONArray(packet.visitedPeers))
        }
        return obj.toString()
    }

    fun decode(raw: String): MeshPacket? {
        return runCatching {
            val obj = JSONObject(raw)
            val visArray = obj.optJSONArray("vis") ?: JSONArray()
            val visited = buildList {
                for (i in 0 until visArray.length()) {
                    add(visArray.getString(i))
                }
            }
            MeshPacket(
                protocolVersion = obj.optInt("v", MeshPacket.PROTOCOL_VERSION),
                messageId = obj.getString("id"),
                senderId = obj.getString("src"),
                destinationId = obj.getString("dst"),
                packetType = PacketType.valueOf(obj.getString("type")),
                createdAt = obj.getLong("ts"),
                expiresAt = obj.getLong("exp"),
                hopLimit = obj.getInt("ttl"),
                hopCount = obj.optInt("hops", 0),
                routingStrategy = RoutingStrategy.valueOf(
                    obj.optString("strat", RoutingStrategy.STORE_AND_FORWARD.name)
                ),
                encryptedPayload = obj.getString("pay"),
                signature = obj.optString("sig", ""),
                integrityHash = obj.optString("hash", ""),
                visitedPeers = visited,
                sprayCopies = obj.optInt("spray", 4),
                priority = obj.optInt("prio", MeshPacket.PRIORITY_NORMAL),
                sourceCluster = obj.optString("clus", "LOCAL")
            )
        }.getOrNull()
    }
}

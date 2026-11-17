package com.example.routing

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.example.core.crypto.MeshCryptoEngine
import com.example.core.protocol.MeshPacket
import com.example.core.protocol.MeshPacketCodec
import com.example.core.protocol.PacketType
import com.example.core.protocol.RoutingStrategy
import com.example.core.protocol.TransportType
import com.example.data.entities.EmergencyBroadcastEntity
import com.example.data.entities.MessageEntity
import com.example.data.entities.PendingPacketEntity
import com.example.data.entities.RouteEntity
import com.example.data.repository.MeshRepository
import com.example.transport.DiscoveredEndpoint
import com.example.transport.MeshTransport
import com.example.transport.TransportEvent
import com.example.transport.permitsFallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class VoiceIncomingClip(
    val messageId: String,
    val senderId: String,
    val senderAlias: String,
    val audioB64: String,
    val durationMs: Long,
    val timestamp: Long
)

data class FileChunkIncoming(
    val packet: MeshPacket,
    val payloadJson: JSONObject
)

class MeshRoutingEngine(
    private val context: Context,
    private val repository: MeshRepository,
    private val crypto: MeshCryptoEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Keep every transport-specific locator for a peer; adapter addresses are not interchangeable.
    private val activeEndpoints = ConcurrentHashMap<String, DiscoveredEndpoint>()
    private val endpointPaths = ConcurrentHashMap<String, ConcurrentHashMap<TransportType, DiscoveredEndpoint>>()
    private val packetReplayStore = PacketReplayStore(context)
    private val transports = mutableListOf<MeshTransport>()
    private val transportPolicy = com.example.transport.TransportPolicyManager(context)
    private val pathHealth = com.example.transport.TransportPathHealthStore(context)

    private val _incomingVoice = MutableSharedFlow<VoiceIncomingClip>(extraBufferCapacity = 16)
    val incomingVoice: SharedFlow<VoiceIncomingClip> = _incomingVoice.asSharedFlow()

    private val _incomingFilePacket = MutableSharedFlow<FileChunkIncoming>(extraBufferCapacity = 64)
    val incomingFilePacket: SharedFlow<FileChunkIncoming> = _incomingFilePacket.asSharedFlow()

    fun registerTransports(list: List<MeshTransport>) {
        transports.clear()
        transports.addAll(list)
        list.forEach { transport ->
            scope.launch {
                transport.events.collect { event ->
                    handleTransportEvent(event)
                }
            }
        }
    }

    fun getDiscoveredEndpoints(): List<DiscoveredEndpoint> = activeEndpoints.values.toList()

    suspend fun triggerAllDiscovery() {
        transports.forEach { runCatching { it.triggerDiscovery() } }
        repository.logNetworkEvent(
            transportType = "MULTI",
            eventType = "DISCOVERY",
            summary = "Triggered discovery scan across ${transports.size} local transports"
        )
    }

    suspend fun sendDirectTextMessage(
        peerId: String,
        plaintext: String,
        strategyOverride: RoutingStrategy? = null,
        ttlHoursOverride: Int? = null,
        isEmergency: Boolean = false
    ): Boolean {
        val local = repository.ensureInitialized()
        val peer = repository.getPeerById(peerId) ?: return false
        val settings = repository.getSettingsOnce()
        val ttlMs = (ttlHoursOverride ?: settings.defaultMessageTtlHours) * 3600_000L
        val expiresAt = System.currentTimeMillis() + ttlMs

        val payloadJson = JSONObject().apply {
            put("text", plaintext)
            put("alias", local.alias)
            put("emergency", isEmergency)
        }.toString()

        val encrypted = if (peer.agreementPublicKey.isNotBlank()) {
            crypto.encryptForPeer(payloadJson, peer.agreementPublicKey, peer.ratchetCounter)
        } else {
            return false
        }

        val strategy = resolveEffectiveStrategy(
            requested = strategyOverride ?: RoutingStrategy.valueOf(settings.defaultRoutingStrategy),
            hasDirectEndpoint = activeEndpoints.containsKey(peerId)
        )

        val packet = crypto.signPacket(
            MeshPacket(
                senderId = local.deviceId,
                destinationId = peerId,
                packetType = if (isEmergency) PacketType.EMERGENCY_BEACON else PacketType.MESSAGE,
                expiresAt = expiresAt,
                hopLimit = settings.defaultHopLimit,
                routingStrategy = strategy,
                encryptedPayload = encrypted,
                visitedPeers = listOf(local.deviceId),
                sprayCopies = settings.sprayCopies,
                priority = if (isEmergency) MeshPacket.PRIORITY_EMERGENCY else MeshPacket.PRIORITY_NORMAL
            )
        )

        repository.saveMessage(
            MessageEntity(
                messageId = packet.messageId,
                conversationId = peerId,
                senderId = local.deviceId,
                senderAlias = local.alias,
                destinationId = peerId,
                isGroup = false,
                isOutgoing = true,
                messageKind = if (isEmergency) "EMERGENCY" else "TEXT",
                body = plaintext,
                deliveryStatus = "QUEUED",
                routingStrategy = strategy.name,
                expiresAt = expiresAt
            )
        )

        return dispatchPacket(packet)
    }

    suspend fun sendGroupMessage(
        groupId: String,
        plaintext: String,
        strategyOverride: RoutingStrategy? = null
    ): Boolean {
        val local = repository.ensureInitialized()
        val group = repository.getGroupById(groupId) ?: return false
        val settings = repository.getSettingsOnce()
        val expiresAt = System.currentTimeMillis() + settings.defaultMessageTtlHours * 3600_000L

        val payloadJson = JSONObject().apply {
            put("groupId", groupId)
            put("text", plaintext)
            put("alias", local.alias)
            put("epoch", group.keyEpoch)
        }.toString()

        val encrypted = crypto.encryptWithGroupKey(payloadJson, group.groupSymmetricKeyB64)
        val packet = crypto.signPacket(
            MeshPacket(
                senderId = local.deviceId,
                destinationId = groupId,
                packetType = PacketType.GROUP_MESSAGE,
                expiresAt = expiresAt,
                hopLimit = settings.defaultHopLimit,
                routingStrategy = strategyOverride ?: RoutingStrategy.EPIDEMIC,
                encryptedPayload = encrypted,
                visitedPeers = listOf(local.deviceId),
                sprayCopies = settings.sprayCopies
            )
        )

        repository.saveMessage(
            MessageEntity(
                messageId = packet.messageId,
                conversationId = groupId,
                senderId = local.deviceId,
                senderAlias = local.alias,
                destinationId = groupId,
                isGroup = true,
                isOutgoing = true,
                messageKind = "TEXT",
                body = plaintext,
                deliveryStatus = "QUEUED",
                routingStrategy = packet.routingStrategy.name,
                expiresAt = expiresAt
            )
        )

        return dispatchPacket(packet)
    }

    suspend fun rotateGroupKeyAndDistribute(groupId: String): Pair<Int, Int> {
        val (group, members) = repository.rotateGroupKeyForDistribution(groupId)
        return distributeGroupKeyUpdate(group, members, revokedPeerId = null)
    }

    suspend fun removeGroupMemberAndRotate(groupId: String, peerId: String): Pair<Int, Int> {
        val (group, remainingMembers) = repository.removeGroupMemberAndRotate(groupId, peerId)
        return distributeGroupKeyUpdate(group, remainingMembers, revokedPeerId = peerId)
    }

    private suspend fun distributeGroupKeyUpdate(
        group: com.example.data.entities.GroupEntity,
        members: List<com.example.data.entities.GroupMemberEntity>,
        revokedPeerId: String?
    ): Pair<Int, Int> {
        val local = repository.ensureInitialized()
        val recipients = members.filter { it.peerId != local.deviceId }
        var queuedOrSent = 0
        recipients.forEach { member ->
            val peer = repository.getPeerById(member.peerId) ?: return@forEach
            if (!peer.verifiedByQr || peer.blocked || peer.agreementPublicKey.isBlank()) return@forEach
            val payload = JSONObject().apply {
                put("groupId", group.groupId)
                put("epoch", group.keyEpoch)
                put("key", group.groupSymmetricKeyB64)
                put("revokedPeerId", revokedPeerId ?: "")
            }.toString()
            val packet = crypto.signPacket(
                MeshPacket(
                    senderId = local.deviceId,
                    destinationId = member.peerId,
                    packetType = PacketType.GROUP_KEY_UPDATE,
                    routingStrategy = RoutingStrategy.STORE_AND_FORWARD,
                    encryptedPayload = crypto.encryptForPeer(payload, peer.agreementPublicKey, peer.ratchetCounter),
                    visitedPeers = listOf(local.deviceId),
                    priority = MeshPacket.PRIORITY_HIGH
                )
            )
            val deliveredNow = dispatchPacket(packet)
            if (deliveredNow || repository.getPendingPacketById(packet.messageId) != null) queuedOrSent++
        }
        repository.logSecurityEvent(
            eventType = "GROUP_KEY_UPDATE_DISTRIBUTION",
            severity = if (queuedOrSent == recipients.size) "INFO" else "WARN",
            summary = "Group epoch ${group.keyEpoch}: queued/sent update to $queuedOrSent of ${recipients.size} remaining member(s); delivery acknowledgements are asynchronous"
        )
        return queuedOrSent to recipients.size
    }
    suspend fun sendGroupInvite(groupId: String, peerId: String): Boolean {
        val local = repository.ensureInitialized()
        val group = repository.getGroupById(groupId) ?: return false
        val peer = repository.getPeerById(peerId) ?: return false
        if (!repository.isGroupAdmin(groupId, local.deviceId)) return false
        if (!peer.verifiedByQr || peer.blocked || peer.agreementPublicKey.isBlank()) return false

        val inviteJson = JSONObject().apply {
            put("groupId", group.groupId)
            put("name", group.name)
            put("desc", group.description)
            put("cat", group.category)
            put("key", group.groupSymmetricKeyB64)
            put("epoch", group.keyEpoch)
            put("by", local.deviceId)
        }.toString()

        val packet = crypto.signPacket(
            MeshPacket(
                senderId = local.deviceId,
                destinationId = peerId,
                packetType = PacketType.GROUP_INVITE,
                routingStrategy = RoutingStrategy.STORE_AND_FORWARD,
                encryptedPayload = crypto.encryptForPeer(inviteJson, peer.agreementPublicKey, peer.ratchetCounter),
                visitedPeers = listOf(local.deviceId),
                priority = MeshPacket.PRIORITY_HIGH
            )
        )
        val deliveredNow = dispatchPacket(packet)
        val queuedOrSent = deliveredNow || repository.getPendingPacketById(packet.messageId) != null
        if (queuedOrSent) repository.addGroupMember(groupId, peer)
        return queuedOrSent
    }

    suspend fun sendEmergencyBroadcast(
        body: String,
        lat: Double? = null,
        lon: Double? = null
    ): EmergencyBroadcastEntity {
        val local = repository.ensureInitialized()
        val settings = repository.getSettingsOnce()
        val msgId = UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put("text", body)
            put("alias", local.alias)
            put("emergency", true)
            if (lat != null && lon != null) {
                put("lat", lat)
                put("lon", lon)
            }
        }.toString()

        val broadcastPacket = crypto.signPacket(
            MeshPacket(
                messageId = msgId,
                senderId = local.deviceId,
                destinationId = MeshPacket.BROADCAST_ID,
                packetType = PacketType.EMERGENCY_BEACON,
                hopLimit = settings.defaultHopLimit.coerceAtLeast(8),
                routingStrategy = RoutingStrategy.EPIDEMIC,
                encryptedPayload = payload,
                visitedPeers = listOf(local.deviceId),
                priority = MeshPacket.PRIORITY_EMERGENCY
            )
        )

        var relayedCount = 0
        activeEndpoints.values.forEach { ep ->
            if (sendOverBestTransport(ep, broadcastPacket)) relayedCount++
        }

        // Send pairwise E2EE copies to known peers for direct ACK correlation
        repository.getUnblockedPeersOnce().forEach { peer ->
            val targetEp = activeEndpoints[peer.deviceId]
            if (targetEp != null && peer.agreementPublicKey.isNotBlank()) {
                val directPkt = crypto.signPacket(
                    MeshPacket(
                        messageId = "${msgId}_${peer.deviceId.take(4)}",
                        senderId = local.deviceId,
                        destinationId = peer.deviceId,
                        packetType = PacketType.EMERGENCY_BEACON,
                        hopLimit = settings.defaultHopLimit.coerceAtLeast(8),
                        routingStrategy = RoutingStrategy.STORE_AND_FORWARD,
                        encryptedPayload = crypto.encryptForPeer(payload, peer.agreementPublicKey, peer.ratchetCounter),
                        visitedPeers = listOf(local.deviceId),
                        priority = MeshPacket.PRIORITY_EMERGENCY
                    )
                )
                if (sendOverBestTransport(targetEp, directPkt)) relayedCount++
            }
        }

        val record = EmergencyBroadcastEntity(
            messageId = msgId,
            senderId = local.deviceId,
            senderAlias = local.alias,
            body = body,
            latitude = lat,
            longitude = lon,
            isOutgoing = true,
            relayCount = relayedCount
        )
        repository.saveEmergencyBroadcast(record)
        repository.logNetworkEvent(
            transportType = "EMERGENCY",
            eventType = "SOS_TX",
            summary = "Dispatched Emergency Broadcast to $relayedCount active link(s)"
        )
        return record
    }

    suspend fun sendLocationBroadcast(
        targetPeerId: String?,
        lat: Double,
        lon: Double,
        accuracyMeters: Float,
        isEmergency: Boolean
    ): Boolean {
        val local = repository.ensureInitialized()
        val settings = repository.getSettingsOnce()
        val dest = targetPeerId ?: MeshPacket.BROADCAST_ID
        val body = JSONObject().apply {
            put("lat", lat)
            put("lon", lon)
            put("acc", accuracyMeters.toDouble())
            put("alias", local.alias)
            put("emergency", isEmergency)
            put("msg", if (isEmergency) settings.emergencyMessage else "Shared coordinates")
        }.toString()

        val payload = if (targetPeerId != null) {
            val peer = repository.getPeerById(targetPeerId)
            if (peer?.agreementPublicKey?.isNotBlank() == true) {
                crypto.encryptForPeer(body, peer.agreementPublicKey, peer.ratchetCounter)
            } else {
                body
            }
        } else {
            body
        }

        val packet = crypto.signPacket(
            MeshPacket(
                senderId = local.deviceId,
                destinationId = dest,
                packetType = if (isEmergency) PacketType.EMERGENCY_BEACON else PacketType.LOCATION_UPDATE,
                hopLimit = settings.defaultHopLimit,
                routingStrategy = RoutingStrategy.EPIDEMIC,
                encryptedPayload = payload,
                visitedPeers = listOf(local.deviceId),
                priority = if (isEmergency) MeshPacket.PRIORITY_EMERGENCY else MeshPacket.PRIORITY_HIGH
            )
        )

        if (targetPeerId != null) {
            repository.saveMessage(
                MessageEntity(
                    messageId = packet.messageId,
                    conversationId = targetPeerId,
                    senderId = local.deviceId,
                    senderAlias = local.alias,
                    destinationId = targetPeerId,
                    isGroup = false,
                    isOutgoing = true,
                    messageKind = if (isEmergency) "EMERGENCY" else "LOCATION",
                    body = "Lat %.5f, Lon %.5f (±%.0fm)".format(lat, lon, accuracyMeters),
                    deliveryStatus = "QUEUED",
                    routingStrategy = packet.routingStrategy.name,
                    expiresAt = packet.expiresAt
                )
            )
        }
        return dispatchPacket(packet)
    }

    suspend fun sendVoicePacket(
        targetPeerId: String,
        audioB64: String,
        durationMs: Long,
        localFilePath: String
    ): Boolean {
        val local = repository.ensureInitialized()
        val peer = repository.getPeerById(targetPeerId) ?: return false
        val settings = repository.getSettingsOnce()

        val inner = JSONObject().apply {
            put("alias", local.alias)
            put("audio", audioB64)
            put("durationMs", durationMs)
        }.toString()

        val encrypted = if (peer.agreementPublicKey.isNotBlank()) {
            crypto.encryptForPeer(inner, peer.agreementPublicKey, peer.ratchetCounter)
        } else {
            return false
        }

        val packet = crypto.signPacket(
            MeshPacket(
                senderId = local.deviceId,
                destinationId = targetPeerId,
                packetType = PacketType.VOICE_PACKET,
                hopLimit = settings.defaultHopLimit,
                routingStrategy = RoutingStrategy.STORE_AND_FORWARD,
                encryptedPayload = encrypted,
                visitedPeers = listOf(local.deviceId),
                priority = MeshPacket.PRIORITY_HIGH
            )
        )

        repository.saveMessage(
            MessageEntity(
                messageId = packet.messageId,
                conversationId = targetPeerId,
                senderId = local.deviceId,
                senderAlias = local.alias,
                destinationId = targetPeerId,
                isGroup = false,
                isOutgoing = true,
                messageKind = "VOICE",
                body = "Voice Note (${durationMs / 1000}s)",
                attachmentPath = localFilePath,
                deliveryStatus = "QUEUED",
                routingStrategy = packet.routingStrategy.name,
                expiresAt = packet.expiresAt
            )
        )
        return dispatchPacket(packet)
    }

    suspend fun sendRawSignedPacket(packet: MeshPacket): Boolean = dispatchPacket(packet)

    suspend fun sendHandshake(endpoint: DiscoveredEndpoint): Boolean {
        val local = repository.ensureInitialized()
        val hello = JSONObject().apply {
            put("alias", local.alias)
            put("sig", local.signingPublicKey)
            put("kx", local.agreementPublicKey)
        }.toString()

        val packet = crypto.signPacket(
            MeshPacket(
                senderId = local.deviceId,
                destinationId = endpoint.deviceId,
                packetType = PacketType.PEER_HANDSHAKE,
                hopLimit = 1,
                routingStrategy = RoutingStrategy.DIRECT,
                encryptedPayload = hello,
                visitedPeers = listOf(local.deviceId)
            )
        )
        val transport = transports.firstOrNull { it.transportType == endpoint.transportType }
        val sent = transport?.sendPacket(endpoint, packet) ?: false
        if (sent) {
            repository.markPeerConnected(
                deviceId = endpoint.deviceId,
                alias = endpoint.alias,
                transport = endpoint.transportType.name,
                address = endpoint.address,
                rssiDbm = endpoint.rssiDbm
            )
            repository.logNetworkEvent(
                transportType = endpoint.transportType.name,
                eventType = "CONNECT",
                peerId = endpoint.deviceId,
                summary = "Sent PEER_HANDSHAKE to ${endpoint.alias}"
            )
        }
        return sent
    }

    suspend fun dispatchPacket(packet: MeshPacket): Boolean {
        val now = System.currentTimeMillis()
        if (packet.isExpired(now) || packet.hopLimit < 0) return false
        packetReplayStore.rememberIfNew(packet.messageId, packet.expiresAt, now)
        val localIdentity = repository.ensureInitialized()
        if (packet.destinationId == localIdentity.deviceId) {
            repository.updateMessageStatus(packet.messageId, "DELIVERED", null, packet.hopCount)
            repository.logNetworkEvent(
                transportType = "LOCAL",
                eventType = "TX_LOOPBACK",
                peerId = localIdentity.deviceId,
                summary = "Delivered packet locally without opening a radio connection"
            )
            return true
        }
        val directEndpoint = activeEndpoints[packet.destinationId]

        if (directEndpoint != null) {
            val sent = sendOverBestTransport(directEndpoint, packet)
            if (sent) {
                repository.updateMessageStatus(packet.messageId, "RELAYED", null, packet.hopCount)
                repository.logNetworkEvent(
                    transportType = directEndpoint.transportType.name,
                    eventType = "TX_DIRECT",
                    peerId = packet.destinationId,
                    summary = "Sent ${packet.packetType.name} directly to ${directEndpoint.alias}"
                )
                return true
            }
        }

        // Check known multi-hop route
        val route = repository.getBestRoute(packet.destinationId)
        if (route != null) {
            val nextHop = activeEndpoints[route.nextHopId]
            if (nextHop != null && !packet.visitedPeers.contains(nextHop.deviceId)) {
                val sent = sendOverBestTransport(nextHop, packet)
                if (sent) {
                    repository.recordRelayOutcome(nextHop.deviceId, true)
                    repository.updateMessageStatus(packet.messageId, "RELAYED", null, packet.hopCount + 1)
                    return true
                }
            }
        }

        // Strategy-driven forwarding. Persist the remaining spray budget, not the original budget.
        var queuedPacket = packet
        when (packet.routingStrategy) {
            RoutingStrategy.EPIDEMIC -> {
                var relayedCount = 0
                activeEndpoints.values.filter { !packet.visitedPeers.contains(it.deviceId) }.forEach { ep ->
                    if (sendOverBestTransport(ep, packet)) relayedCount++
                }
                if (relayedCount > 0) {
                    repository.updateMessageStatus(packet.messageId, "RELAYED", null, packet.hopCount)
                }
            }
            RoutingStrategy.SPRAY_AND_WAIT -> {
                var copiesLeft = packet.sprayCopies
                if (copiesLeft > 1) {
                    val candidates = activeEndpoints.values.filter { !packet.visitedPeers.contains(it.deviceId) }
                    for (ep in candidates) {
                        if (copiesLeft <= 1) break
                        val half = (copiesLeft / 2).coerceAtLeast(1)
                        val sprayed = packet.copy(sprayCopies = half)
                        if (sendOverBestTransport(ep, sprayed)) {
                            copiesLeft -= half
                        }
                    }
                    if (copiesLeft < packet.sprayCopies) {
                        queuedPacket = packet.copy(sprayCopies = copiesLeft)
                        repository.updateMessageStatus(packet.messageId, "SPRAYED", null, packet.hopCount)
                    }
                }
            }
            else -> Unit
        }

        // Persist in DTN Store-and-Forward queue
        repository.enqueuePendingPacket(
            PendingPacketEntity(
                messageId = packet.messageId,
                destinationId = packet.destinationId,
                packetType = packet.packetType.name,
                routingStrategy = packet.routingStrategy.name,
                rawPacketJson = MeshPacketCodec.encode(queuedPacket),
                priority = queuedPacket.priority,
                remainingSprayCopies = queuedPacket.sprayCopies,
                hopLimit = queuedPacket.hopLimit,
                expiresAt = queuedPacket.expiresAt
            )
        )
        return false
    }

    suspend fun flushStoreAndForwardQueue() {
        val pending = repository.getActivePendingPackets()
        if (pending.isEmpty() || activeEndpoints.isEmpty()) return

        for (entry in pending) {
            val packet = MeshPacketCodec.decode(entry.rawPacketJson) ?: run {
                repository.removePendingPacket(entry.messageId)
                continue
            }
            if (packet.isExpired()) {
                repository.removePendingPacket(entry.messageId)
                repository.updateMessageStatus(entry.messageId, "EXPIRED", null, packet.hopCount)
                continue
            }

            val direct = activeEndpoints[packet.destinationId]
            val dispatched = dispatchPacket(packet)
            if (dispatched) {
                repository.removePendingPacket(entry.messageId)
                repository.updateMessageStatus(entry.messageId, "RELAYED", null, packet.hopCount)
                repository.logNetworkEvent(
                    transportType = direct?.transportType?.name ?: "DTN",
                    eventType = "DTN_FLUSH",
                    peerId = direct?.deviceId ?: packet.destinationId,
                    summary = "Forwarded queued DTN packet ${packet.messageId.take(8)} after a route became available"
                )
            }
        }
    }

    suspend fun runSelfLoopbackDiagnostic(): String {
        val local = repository.ensureInitialized()
        val testId = "diag_${UUID.randomUUID().toString().take(8)}"
        val payloadText = "MeshLink Loopback Self-Test @ ${System.currentTimeMillis()}"
        val encrypted = crypto.encryptForPeer(payloadText, local.agreementPublicKey, 0)
        val packet = crypto.signPacket(
            MeshPacket(
                messageId = testId,
                senderId = local.deviceId,
                destinationId = local.deviceId,
                packetType = PacketType.MESSAGE,
                routingStrategy = RoutingStrategy.DIRECT,
                encryptedPayload = encrypted
            )
        )
        val encoded = MeshPacketCodec.encode(packet)
        val decoded = MeshPacketCodec.decode(encoded)
            ?: return "FAIL: Packet codec decode failed"
        val sigOk = crypto.verifyPacketSignature(decoded, local.signingPublicKey)
        if (!sigOk) return "FAIL: ECDSA signature verification failed"
        val decrypted = crypto.decryptFromPeer(decoded.encryptedPayload, local.agreementPublicKey, 0)
            ?: return "FAIL: ECDH + AES-256-GCM decrypt failed"
        if (decrypted != payloadText) return "FAIL: Plaintext mismatch"

        repository.logNetworkEvent(
            transportType = "LOOPBACK",
            eventType = "LOOPBACK_TEST",
            peerId = local.deviceId,
            summary = "Self-test passed: MLP codec + ECDSA P-256 + ECDH AES-256-GCM verified"
        )
        return "PASS: MLP frame (${encoded.length}B), ECDSA-SHA256 sig & AES-256-GCM E2EE verified"
    }

    private suspend fun handleTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.PeerFound -> {
                val ep = event.endpoint
                // Bonded Bluetooth devices are not automatically MeshLink identities.
                // RFCOMM discovery currently has no authenticated identity handshake,
                // so never create a trusted peer or route from its MAC-derived ID.
                if (ep.transportType == TransportType.BLUETOOTH_CLASSIC &&
                    (ep.signingPublicKey.isBlank() || ep.agreementPublicKey.isBlank())
                ) {
                    repository.logNetworkEvent(
                        transportType = ep.transportType.name,
                        eventType = "IDENTITY_PENDING",
                        peerId = ep.deviceId,
                        summary = "Bonded Bluetooth device found; MeshLink identity exchange is required before peer registration"
                    )
                    return
                }
                endpointPaths.getOrPut(ep.deviceId) { ConcurrentHashMap() }[ep.transportType] = ep
                val best = selectPreferredEndpoint(ep.deviceId) ?: ep
                activeEndpoints[ep.deviceId] = best
                val peer = repository.upsertDiscoveredPeer(
                    deviceId = ep.deviceId,
                    alias = ep.alias,
                    signingPublicKey = ep.signingPublicKey,
                    agreementPublicKey = ep.agreementPublicKey,
                    transport = ep.transportType.name,
                    rssiDbm = ep.rssiDbm,
                    address = ep.address,
                    port = ep.port,
                    connected = true
                )
                if (!peer.blocked) {
                    repository.upsertRoute(
                        RouteEntity(
                            destinationId = ep.deviceId,
                            nextHopId = ep.deviceId,
                            transportType = ep.transportType.name,
                            hopCount = 1,
                            qualityScore = (100 + ep.rssiDbm).coerceIn(10, 100)
                        )
                    )
                    flushStoreAndForwardQueue()
                }
            }

            is TransportEvent.PacketReceived -> {
                processIncomingPacket(event.packet, event.transportType, event.remoteAddress)
            }

            is TransportEvent.ConnectionChanged -> {
                if (event.connected) {
                    repository.markPeerConnected(
                        event.peerId,
                        event.peerId,
                        event.transportType.name,
                        event.address
                    )
                    flushStoreAndForwardQueue()
                } else {
                    endpointPaths[event.peerId]?.remove(event.transportType)
                    val remaining = selectPreferredEndpoint(event.peerId)
                    if (remaining != null) {
                        activeEndpoints[event.peerId] = remaining
                    } else {
                        endpointPaths.remove(event.peerId)
                        activeEndpoints.remove(event.peerId)
                        repository.markPeerDisconnected(event.peerId)
                    }
                }
            }

            is TransportEvent.Log -> {
                repository.logNetworkEvent(
                    transportType = event.transportType.name,
                    eventType = event.eventType,
                    summary = event.message
                )
            }
        }
    }

    private suspend fun processIncomingPacket(
        packet: MeshPacket,
        transportType: TransportType,
        remoteAddress: String
    ) {
        val local = repository.ensureInitialized()
        if (packet.senderId == local.deviceId) return

        // Replay & duplicate suppression. Expired entries are pruned and the cache is bounded.
        val now = System.currentTimeMillis()
        if (packet.isExpired(now)) return
        if (packetReplayStore.contains(packet.messageId, now)) {
            repository.logSecurityEvent(
                eventType = "REPLAY_DROP",
                peerId = packet.senderId,
                summary = "Dropped duplicate/replay packet ${packet.messageId.take(8)}"
            )
            return
        }
        val senderPeer = repository.getPeerById(packet.senderId)
        if (senderPeer?.blocked == true) {
            repository.logSecurityEvent(
                eventType = "BLOCKED_DROP",
                peerId = packet.senderId,
                severity = "WARN",
                summary = "Rejected packet from blocked peer ${packet.senderId}"
            )
            return
        }

        // The first packet from an unknown node must be a signed identity handshake.
        if (packet.packetType == PacketType.PEER_HANDSHAKE || packet.packetType == PacketType.HELLO) {
            val obj = runCatching { JSONObject(packet.encryptedPayload) }.getOrNull() ?: return
            val alias = obj.optString("alias", "Node-${packet.senderId.take(6)}")
            val sig = obj.optString("sig", "")
            val kx = obj.optString("kx", "")
            if (sig.isBlank() || kx.isBlank() || !crypto.verifyPacketSignature(packet, sig)) {
                repository.logSecurityEvent(
                    eventType = "HANDSHAKE_REJECT",
                    peerId = packet.senderId,
                    severity = "ALERT",
                    summary = "Rejected unsigned or invalid peer identity handshake"
                )
                return
            }
            if (senderPeer != null &&
                ((senderPeer.signingPublicKey.isNotBlank() && senderPeer.signingPublicKey != sig) ||
                 (senderPeer.agreementPublicKey.isNotBlank() && senderPeer.agreementPublicKey != kx))
            ) {
                repository.logSecurityEvent(
                    eventType = "IDENTITY_KEY_CHANGE",
                    peerId = packet.senderId,
                    severity = "ALERT",
                    summary = "Rejected unexpected public-key change for an existing peer"
                )
                return
            }
            if (!packetReplayStore.rememberIfNew(packet.messageId, packet.expiresAt, now)) {
                repository.logSecurityEvent(
                    eventType = "REPLAY_DROP",
                    peerId = packet.senderId,
                    summary = "Dropped concurrent duplicate handshake ${packet.messageId.take(8)}"
                )
                return
            }
            val needsReply = senderPeer == null || senderPeer.signingPublicKey.isBlank()
            repository.upsertDiscoveredPeer(
                deviceId = packet.senderId,
                alias = alias,
                signingPublicKey = sig,
                agreementPublicKey = kx,
                transport = transportType.name,
                address = remoteAddress,
                port = if (transportType == TransportType.LOCAL_LAN || transportType == TransportType.WIFI_DIRECT) {
                    LanMeshTransportPortHolder.TCP_PORT
                } else 0,
                connected = true
            )
            val handshakeEndpoint = DiscoveredEndpoint(
                deviceId = packet.senderId,
                alias = alias,
                signingPublicKey = sig,
                agreementPublicKey = kx,
                transportType = transportType,
                address = remoteAddress,
                port = if (transportType == TransportType.LOCAL_LAN || transportType == TransportType.WIFI_DIRECT) {
                    LanMeshTransportPortHolder.TCP_PORT
                } else 0,
                rssiDbm = -60
            )
            endpointPaths.getOrPut(packet.senderId) { ConcurrentHashMap() }[transportType] = handshakeEndpoint
            activeEndpoints[packet.senderId] = selectPreferredEndpoint(packet.senderId) ?: handshakeEndpoint
            if (needsReply) {
                // Complete the handshake in both directions so each side learns the other's
                // stable application identity, not only a transport-level MAC address.
                sendHandshake(handshakeEndpoint)
            }
            return
        }

        // Never accept data or delivery acknowledgements from an unknown/unpinned key.
        if (senderPeer == null || senderPeer.signingPublicKey.isBlank()) {
            repository.logSecurityEvent(
                eventType = "UNKNOWN_IDENTITY_DROP",
                peerId = packet.senderId,
                severity = "WARN",
                summary = "Dropped non-handshake packet before peer identity was established"
            )
            return
        }
        if (!crypto.verifyPacketSignature(packet, senderPeer.signingPublicKey)) {
            repository.logSecurityEvent(
                eventType = "SIG_FAIL",
                peerId = packet.senderId,
                severity = "ALERT",
                summary = "Signature/integrity verification failed for ${packet.messageId.take(8)}"
            )
            return
        }
        if (!packetReplayStore.rememberIfNew(packet.messageId, packet.expiresAt, now)) {
            repository.logSecurityEvent(
                eventType = "REPLAY_DROP",
                peerId = packet.senderId,
                summary = "Dropped concurrent duplicate/replay packet ${packet.messageId.take(8)}"
            )
            return
        }

        val isForLocal = packet.destinationId == local.deviceId ||
            packet.destinationId == MeshPacket.BROADCAST_ID

        if (isForLocal || packet.packetType == PacketType.GROUP_MESSAGE) {
            deliverToLocalNode(packet, transportType)
        }

        // Network Bridge cross-cluster forwarding
        bridgePacketIfConfigured(packet, transportType, senderPeer?.verifiedByQr == true)

        // Multi-hop relay forwarding without reading plaintext payload
        val shouldRelay = packet.destinationId != local.deviceId && packet.hopLimit > 1
        if (shouldRelay) {
            val forwarded = packet.copy(
                hopLimit = packet.hopLimit - 1,
                hopCount = packet.hopCount + 1,
                visitedPeers = packet.visitedPeers + local.deviceId
            )
            dispatchPacket(forwarded)
            repository.logNetworkEvent(
                transportType = transportType.name,
                eventType = "RELAY_FWD",
                peerId = packet.destinationId,
                summary = "Relayed encrypted ${packet.packetType.name} (${forwarded.hopCount} hops, TTL=${forwarded.hopLimit})"
            )
        }
    }

    private suspend fun bridgePacketIfConfigured(
        packet: MeshPacket,
        incomingTransport: TransportType,
        senderVerified: Boolean
    ) {
        val settings = repository.getSettingsOnce()
        if (!settings.bridgeEnabled) return
        val local = repository.ensureInitialized()
        if (packet.visitedPeers.contains(local.deviceId)) return
        if (settings.bridgeRequireVerifiedPeers && !senderVerified) return
        if (!settings.bridgeAllowBroadcasts && packet.destinationId == MeshPacket.BROADCAST_ID) return

        val netA = settings.bridgeNetworkA
        val netB = settings.bridgeNetworkB
        if (netA == netB) return

        val targetNetwork = when (incomingTransport.name) {
            netA -> netB
            netB -> netA
            else -> return
        }

        val bridgedPacket = packet.copy(
            hopLimit = (packet.hopLimit - 1).coerceAtLeast(1),
            hopCount = packet.hopCount + 1,
            visitedPeers = packet.visitedPeers + local.deviceId,
            sourceCluster = "${incomingTransport.name}->$targetNetwork"
        )

        val targetEndpoints = activeEndpoints.values.filter {
            it.transportType.name == targetNetwork && !packet.visitedPeers.contains(it.deviceId)
        }
        val targetTransport = transports.firstOrNull { it.transportType.name == targetNetwork } ?: return

        var forwarded = 0
        for (ep in targetEndpoints) {
            if (targetTransport.sendPacket(ep, bridgedPacket)) {
                forwarded++
            }
        }
        if (forwarded > 0) {
            repository.incrementBridgeForwardedCount()
            repository.logNetworkEvent(
                transportType = "BRIDGE",
                eventType = "BRIDGE_FWD",
                peerId = packet.destinationId,
                summary = "Bridged ${packet.packetType.name} ($netA ⇄ $netB) across $forwarded link(s)"
            )
        }
    }

    private suspend fun deliverToLocalNode(packet: MeshPacket, transportType: TransportType) {
        val local = repository.ensureInitialized()
        val senderPeer = repository.getPeerById(packet.senderId)

        when (packet.packetType) {
            PacketType.DELIVERY_ACK -> {
                val obj = runCatching { JSONObject(packet.encryptedPayload) }.getOrNull()
                val ackId = obj?.optString("ackFor") ?: return
                val baseEmergencyId = ackId.substringBefore("_")
                repository.updateMessageStatus(ackId, "DELIVERED", System.currentTimeMillis(), packet.hopCount)
                repository.removePendingPacket(ackId)
                val ackAlias = senderPeer?.alias ?: packet.senderId
                repository.recordEmergencyAck(baseEmergencyId, packet.senderId, ackAlias)
                repository.logNetworkEvent(
                    transportType = transportType.name,
                    eventType = "ACK_RX",
                    peerId = packet.senderId,
                    summary = "Delivery ACK received for ${ackId.take(8)} from $ackAlias"
                )
            }

            PacketType.MESSAGE, PacketType.EMERGENCY_BEACON -> {
                val plain = if (packet.destinationId == MeshPacket.BROADCAST_ID) {
                    packet.encryptedPayload
                } else if (senderPeer?.agreementPublicKey?.isNotBlank() == true) {
                    crypto.decryptFromPeer(packet.encryptedPayload, senderPeer.agreementPublicKey, senderPeer.ratchetCounter)
                } else {
                    null
                } ?: return

                val obj = runCatching { JSONObject(plain) }.getOrNull() ?: return
                val text = obj.optString("text", obj.optString("msg", ""))
                val alias = obj.optString("alias", senderPeer?.alias ?: packet.senderId)
                val isEmergency = packet.packetType == PacketType.EMERGENCY_BEACON || obj.optBoolean("emergency", false)
                if (isEmergency) {
                    val lat = if (obj.has("lat")) obj.optDouble("lat") else null
                    val lon = if (obj.has("lon")) obj.optDouble("lon") else null
                    repository.saveEmergencyBroadcast(
                        EmergencyBroadcastEntity(
                            messageId = packet.messageId,
                            senderId = packet.senderId,
                            senderAlias = alias,
                            body = text,
                            latitude = lat,
                            longitude = lon,
                            isOutgoing = false
                        )
                    )
                }
                repository.saveMessage(
                    MessageEntity(
                        messageId = packet.messageId,
                        conversationId = packet.senderId,
                        senderId = packet.senderId,
                        senderAlias = alias,
                        destinationId = local.deviceId,
                        isGroup = false,
                        isOutgoing = false,
                        messageKind = if (isEmergency) "EMERGENCY" else "TEXT",
                        body = text,
                        deliveryStatus = "DELIVERED",
                        routingStrategy = packet.routingStrategy.name,
                        hopCount = packet.hopCount,
                        expiresAt = packet.expiresAt,
                        deliveredAt = System.currentTimeMillis()
                    )
                )
                sendDeliveryAck(packet.senderId, packet.messageId)
            }

            PacketType.GROUP_INVITE -> {
                if (senderPeer?.verifiedByQr != true || senderPeer.blocked) return
                val plain = if (senderPeer.agreementPublicKey.isNotBlank()) {
                    crypto.decryptFromPeer(packet.encryptedPayload, senderPeer.agreementPublicKey, senderPeer.ratchetCounter)
                } else return
                val obj = runCatching { JSONObject(plain) }.getOrNull() ?: return
                val groupId = obj.optString("groupId")
                val createdBy = obj.optString("by", packet.senderId)
                val groupKey = obj.optString("key")
                if (groupId.isBlank() || createdBy != packet.senderId || !crypto.isValidGroupKeyB64(groupKey)) {
                    repository.logSecurityEvent(
                        eventType = "GROUP_INVITE_REJECTED",
                        peerId = packet.senderId,
                        severity = "WARN",
                        summary = "Rejected malformed group invite or creator identity mismatch"
                    )
                    return
                }
                val existingGroup = repository.getGroupById(groupId)
                if (existingGroup != null && !repository.isGroupAdmin(groupId, packet.senderId)) {
                    repository.logSecurityEvent(
                        eventType = "GROUP_INVITE_REJECTED",
                        peerId = packet.senderId,
                        severity = "WARN",
                        summary = "Rejected group invite from a non-admin member"
                    )
                    return
                }
                val invite = MeshCryptoEngine.GroupInvitePayload(
                    groupId = groupId,
                    name = obj.optString("name", "Community Group"),
                    description = obj.optString("desc", ""),
                    category = obj.optString("cat", "COMMUNITY"),
                    groupKeyB64 = groupKey,
                    epoch = obj.optInt("epoch", 1),
                    createdBy = createdBy
                )
                repository.joinGroupFromInvite(invite)
                sendDeliveryAck(packet.senderId, packet.messageId)
            }

            PacketType.GROUP_KEY_UPDATE -> {
                if (senderPeer?.verifiedByQr != true || senderPeer.blocked) return
                val plain = crypto.decryptFromPeer(
                    packet.encryptedPayload,
                    senderPeer.agreementPublicKey,
                    senderPeer.ratchetCounter
                ) ?: return
                val obj = runCatching { JSONObject(plain) }.getOrNull() ?: return
                val groupId = obj.optString("groupId")
                val epoch = obj.optInt("epoch", -1)
                val key = obj.optString("key")
                val revoked = obj.optString("revokedPeerId").takeIf { it.isNotBlank() }
                if (groupId.isBlank() || epoch <= 0 || key.isBlank()) return
                val applied = repository.applyGroupKeyUpdate(
                    groupId = groupId,
                    epoch = epoch,
                    groupKeyB64 = key,
                    senderId = packet.senderId,
                    revokedPeerId = revoked
                )
                if (applied) {
                    repository.logNetworkEvent(
                        transportType = transportType.name,
                        eventType = "GROUP_KEY_EPOCH_APPLIED",
                        peerId = packet.senderId,
                        summary = "Applied group $groupId key epoch $epoch"
                    )
                    sendDeliveryAck(packet.senderId, packet.messageId)
                } else {
                    repository.logSecurityEvent(
                        eventType = "GROUP_KEY_UPDATE_REJECTED",
                        peerId = packet.senderId,
                        severity = "WARN",
                        summary = "Rejected unauthorized, stale or malformed group key update"
                    )
                }
            }

            PacketType.GROUP_MESSAGE -> {
                val group = repository.getGroupById(packet.destinationId) ?: return
                val plain = crypto.decryptWithGroupKey(packet.encryptedPayload, group.groupSymmetricKeyB64)
                    ?: return
                val obj = runCatching { JSONObject(plain) }.getOrNull() ?: return
                repository.saveMessage(
                    MessageEntity(
                        messageId = packet.messageId,
                        conversationId = group.groupId,
                        senderId = packet.senderId,
                        senderAlias = obj.optString("alias", packet.senderId),
                        destinationId = group.groupId,
                        isGroup = true,
                        isOutgoing = false,
                        messageKind = "TEXT",
                        body = obj.optString("text"),
                        deliveryStatus = "DELIVERED",
                        routingStrategy = packet.routingStrategy.name,
                        hopCount = packet.hopCount,
                        expiresAt = packet.expiresAt,
                        deliveredAt = System.currentTimeMillis()
                    )
                )
                sendDeliveryAck(packet.senderId, packet.messageId)
            }

            PacketType.VOICE_PACKET -> {
                val plain = if (senderPeer?.agreementPublicKey?.isNotBlank() == true) {
                    crypto.decryptFromPeer(packet.encryptedPayload, senderPeer.agreementPublicKey, senderPeer.ratchetCounter)
                } else null
                val obj = plain?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
                val durationMs = obj.optLong("durationMs", 2000L)
                val audioB64 = obj.optString("audio", "")
                val alias = obj.optString("alias", senderPeer?.alias ?: packet.senderId)
                repository.saveMessage(
                    MessageEntity(
                        messageId = packet.messageId,
                        conversationId = packet.senderId,
                        senderId = packet.senderId,
                        senderAlias = alias,
                        destinationId = local.deviceId,
                        isGroup = false,
                        isOutgoing = false,
                        messageKind = "VOICE",
                        body = "Voice Note (${durationMs / 1000}s)",
                        deliveryStatus = "DELIVERED",
                        routingStrategy = packet.routingStrategy.name,
                        hopCount = packet.hopCount,
                        expiresAt = packet.expiresAt,
                        deliveredAt = System.currentTimeMillis()
                    )
                )
                _incomingVoice.emit(
                    VoiceIncomingClip(
                        messageId = packet.messageId,
                        senderId = packet.senderId,
                        senderAlias = alias,
                        audioB64 = audioB64,
                        durationMs = durationMs,
                        timestamp = packet.createdAt
                    )
                )
                sendDeliveryAck(packet.senderId, packet.messageId)
            }

            PacketType.LOCATION_UPDATE -> {
                val decrypted = if (senderPeer?.agreementPublicKey?.isNotBlank() == true &&
                    packet.destinationId != MeshPacket.BROADCAST_ID
                ) {
                    crypto.decryptFromPeer(packet.encryptedPayload, senderPeer.agreementPublicKey, senderPeer.ratchetCounter)
                } else {
                    packet.encryptedPayload
                } ?: return
                val obj = runCatching { JSONObject(decrypted) }.getOrNull() ?: return
                val lat = obj.optDouble("lat", Double.NaN)
                val lon = obj.optDouble("lon", Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) {
                    repository.updatePeerLocation(packet.senderId, lat, lon, packet.createdAt)
                }
            }

            PacketType.FILE_OFFER, PacketType.FILE_CHUNK -> {
                val plain = if (senderPeer?.agreementPublicKey?.isNotBlank() == true) {
                    crypto.decryptFromPeer(packet.encryptedPayload, senderPeer.agreementPublicKey, senderPeer.ratchetCounter)
                } else packet.encryptedPayload
                val obj = plain?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
                _incomingFilePacket.emit(FileChunkIncoming(packet, obj))
            }

            else -> Unit
        }
    }

    private suspend fun sendDeliveryAck(destinationId: String, ackForMessageId: String) {
        val local = repository.ensureInitialized()
        val ackPayload = JSONObject().put("ackFor", ackForMessageId).toString()
        val ackPacket = crypto.signPacket(
            MeshPacket(
                senderId = local.deviceId,
                destinationId = destinationId,
                packetType = PacketType.DELIVERY_ACK,
                hopLimit = 6,
                routingStrategy = RoutingStrategy.DIRECT,
                encryptedPayload = ackPayload,
                visitedPeers = listOf(local.deviceId)
            )
        )
        dispatchPacket(ackPacket)
    }

    private fun selectPreferredEndpoint(peerId: String): DiscoveredEndpoint? {
        val policy = transportPolicy.load()
        val preference = policy.preferred.withIndex().associate { it.value to it.index }
        return endpointPaths[peerId]?.values
            ?.filter { it.transportType !in policy.disabled }
            ?.filter { pathHealth.shouldAttempt(peerId, it.transportType) }
            ?.filter { ep -> transports.any { it.transportType == ep.transportType && it.isRunning } }
            ?.filter {
                policy.mode != com.example.transport.TransportSelectionMode.ONLY ||
                    it.transportType in policy.preferred
            }
            ?.filter {
                policy.mode != com.example.transport.TransportSelectionMode.PREFER ||
                    policy.allowFallback || it.transportType in policy.preferred
            }
            ?.minWithOrNull(
                compareBy<DiscoveredEndpoint> { preference[it.transportType] ?: Int.MAX_VALUE }
                    .thenByDescending { it.rssiDbm }
                    .thenByDescending { it.lastSeenAt }
            )
    }

    private suspend fun sendOverBestTransport(endpoint: DiscoveredEndpoint, packet: MeshPacket): Boolean {
        val policy = transportPolicy.load()
        val packetSize = MeshPacketCodec.encode(packet).toByteArray(Charsets.UTF_8).size
        val candidates = (endpointPaths[endpoint.deviceId]?.values?.toList().orEmpty() + endpoint)
            .distinctBy { it.transportType }
            .filter { it.transportType !in policy.disabled }
            .filter { pathHealth.shouldAttempt(endpoint.deviceId, it.transportType) }
            .filter { ep -> transports.any { it.transportType == ep.transportType && it.isRunning } }
            .filter { packetSize <= it.transportType.maxPayloadBytes }
            .filter {
                policy.mode != com.example.transport.TransportSelectionMode.ONLY ||
                    it.transportType in policy.preferred
            }
            .filter {
                policy.mode != com.example.transport.TransportSelectionMode.PREFER ||
                    policy.allowFallback || it.transportType in policy.preferred
            }

        if (candidates.isEmpty()) return false
        val preference = policy.preferred.withIndex().associate { it.value to it.index }
        val ordered = candidates.sortedWith(
            compareBy<DiscoveredEndpoint> { preference[it.transportType] ?: Int.MAX_VALUE }
                .thenBy { pathHealth.failureCount(endpoint.deviceId, it.transportType) }
                .thenByDescending { it.rssiDbm }
                .thenByDescending { it.lastSeenAt }
        )
        val attempts = if (!policy.permitsFallback()) ordered.take(1) else ordered

        // Each adapter receives only the locator discovered by that same adapter.
        for (candidate in attempts) {
            val adapter = transports.firstOrNull {
                it.transportType == candidate.transportType && it.isRunning
            } ?: continue
            val sent = runCatching { adapter.sendPacket(candidate, packet) }.getOrDefault(false)
            if (sent) {
                pathHealth.recordSuccess(candidate.deviceId, candidate.transportType)
                activeEndpoints[candidate.deviceId] = candidate
                return true
            }
            pathHealth.recordFailure(candidate.deviceId, candidate.transportType)
        }
        return false
    }

    private fun resolveEffectiveStrategy(
        requested: RoutingStrategy,
        hasDirectEndpoint: Boolean
    ): RoutingStrategy {
        if (requested != RoutingStrategy.AUTONOMOUS) return requested
        if (hasDirectEndpoint) return RoutingStrategy.DIRECT
        val batteryPct = getBatteryPercentage()
        return when {
            batteryPct in 1..20 -> RoutingStrategy.STORE_AND_FORWARD
            activeEndpoints.size >= 3 -> RoutingStrategy.SPRAY_AND_WAIT
            activeEndpoints.isNotEmpty() -> RoutingStrategy.EPIDEMIC
            else -> RoutingStrategy.STORE_AND_FORWARD
        }
    }

    private fun getBatteryPercentage(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return 100
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100) / scale else 100
    }

    private object LanMeshTransportPortHolder {
        const val TCP_PORT = 45821
    }

    companion object {
        private const val MAX_SEEN_PACKETS = 10_000
    }
}

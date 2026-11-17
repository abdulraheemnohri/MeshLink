package com.example.transport

import android.content.Context
import com.example.core.crypto.LocalIdentityKeys
import com.example.core.protocol.MeshPacket
import com.example.core.protocol.MeshPacketCodec
import com.example.core.protocol.PacketType
import com.example.core.protocol.TransportType
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Nearby endpoint IDs are temporary locators; MeshLink identity requires signed HELLO and nonce proof. */
class NearbyConnectionsMeshTransport(
    context: Context,
    private val localIdentityProvider: () -> LocalIdentityKeys,
    private val packetSigner: (MeshPacket) -> MeshPacket,
    private val packetVerifier: (MeshPacket, String) -> Boolean
) : MeshTransport {
    override val transportType = TransportType.NEARBY_CONNECTIONS
    private val appContext = context.applicationContext
    private val client = Nearby.getConnectionsClient(appContext)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val eventFlow = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 96)
    override val events: Flow<TransportEvent> = eventFlow.asSharedFlow()

    private data class Remote(val id: String, val alias: String, val signing: String, val agreement: String)
    private data class Challenge(val packetId: String, val nonce: String, val at: Long)
    private val identities = ConcurrentHashMap<String, Remote>()
    private val peerByEndpoint = ConcurrentHashMap<String, String>()
    private val endpointByPeer = ConcurrentHashMap<String, String>()
    private val challenges = ConcurrentHashMap<String, Challenge>()
    private val authenticated = ConcurrentHashMap.newKeySet<String>()
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val seen = ConcurrentHashMap<String, Long>()
    @Volatile override var isRunning = false
        private set

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            if (bytes.isEmpty() || bytes.size > MAX_FRAME_BYTES) return
            val packet = MeshPacketCodec.decode(String(bytes, Charsets.UTF_8)) ?: return
            when (packet.packetType) {
                PacketType.HELLO -> acceptHello(endpointId, packet)
                PacketType.PEER_HANDSHAKE -> acceptProof(endpointId, packet)
                else -> {
                    val peerId = peerByEndpoint[endpointId]
                    if (endpointId in authenticated && peerId == packet.senderId) {
                        scope.launch { eventFlow.emit(TransportEvent.PacketReceived(packet, transportType, endpointId)) }
                    } else log("UNAUTHENTICATED_FRAME_DROPPED", "Dropped Nearby data before identity proof")
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, payloadCallback).addOnFailureListener {
                log("ACCEPT_FAILED", it.message ?: "Nearby connection acceptance failed")
                client.disconnectFromEndpoint(endpointId)
            }
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                scope.launch { sendHello(endpointId) }
            } else {
                requested.remove(endpointId)
                waiters.remove(endpointId)?.complete(false)
                log("CONNECTION_FAILED", "Nearby status ${result.status.statusCode}")
            }
        }
        override fun onDisconnected(endpointId: String) {
            val peerId = peerByEndpoint.remove(endpointId)
            if (peerId != null) {
                endpointByPeer.remove(peerId, endpointId)
                scope.launch { eventFlow.emit(TransportEvent.ConnectionChanged(peerId, transportType, false, endpointId)) }
            }
            identities.remove(endpointId)
            challenges.remove(endpointId)
            authenticated.remove(endpointId)
            waiters.remove(endpointId)?.complete(false)
            requested.remove(endpointId)
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!isRunning) return
            // Deterministic initiator selection prevents both peers requesting each other at once.
            val localId = localIdentityProvider().deviceId
            if (localId >= info.endpointName || !requested.add(endpointId)) return
            client.requestConnection(localId, endpointId, lifecycle)
                .addOnFailureListener {
                    requested.remove(endpointId)
                    log("REQUEST_FAILED", it.message ?: "Nearby request failed")
                }
        }
        override fun onEndpointLost(endpointId: String) { requested.remove(endpointId) }
    }

    override suspend fun start() = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
        if (availability != ConnectionResult.SUCCESS) {
            throw IllegalStateException("Google Play services unavailable (status $availability)")
        }
        val identity = localIdentityProvider()
        val advertising = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val discovering = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        awaitCompletion(client.startAdvertising(identity.deviceId, SERVICE_ID, lifecycle, advertising))
        isRunning = true
        try {
            awaitCompletion(client.startDiscovery(SERVICE_ID, discovery, discovering))
            log("STARTED", "Nearby Connections advertising and discovery active")
        } catch (error: Exception) {
            isRunning = false
            runCatching { client.stopDiscovery() }
            runCatching { client.stopAdvertising() }
            throw error
        }
    }

    override suspend fun triggerDiscovery() {
        check(isRunning) { "Nearby Connections is not running" }
        client.stopDiscovery()
        awaitCompletion(client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()))
    }

    override suspend fun connect(endpoint: DiscoveredEndpoint): Boolean {
        if (endpoint.transportType != transportType || endpoint.address.isBlank()) return false
        if (endpoint.address in authenticated) return true
        val waiter = CompletableDeferred<Boolean>()
        waiters[endpoint.address] = waiter
        if (requested.add(endpoint.address)) {
            client.requestConnection(localIdentityProvider().deviceId, endpoint.address, lifecycle)
                .addOnFailureListener {
                    requested.remove(endpoint.address)
                    waiters.remove(endpoint.address)?.complete(false)
                }
        }
        return try { withTimeoutOrNull(CONNECT_TIMEOUT_MS) { waiter.await() } == true }
        finally { waiters.remove(endpoint.address, waiter) }
    }

    override suspend fun sendPacket(endpoint: DiscoveredEndpoint, packet: MeshPacket): Boolean {
        if (endpoint.transportType != transportType) return false
        val endpointId = endpointByPeer[endpoint.deviceId] ?: endpoint.address
        if (endpointId !in authenticated) return false
        val bytes = MeshPacketCodec.encode(packet).toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_FRAME_BYTES) return false
        return runCatching { awaitCompletion(client.sendPayload(endpointId, Payload.fromBytes(bytes))); true }
            .getOrElse { log("SEND_FAILED", it.message ?: "Nearby send failed"); false }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        isRunning = false
        runCatching { awaitCompletion(client.stopAdvertising()) }
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
        waiters.values.forEach { it.complete(false) }
        waiters.clear(); requested.clear(); authenticated.clear(); identities.clear()
        peerByEndpoint.clear(); endpointByPeer.clear(); challenges.clear(); seen.clear()
        log("STOPPED", "Nearby Connections stopped")
    }

    private suspend fun awaitCompletion(task: Task<Void>) { task.await() }

    private suspend fun sendHello(endpointId: String) {
        val local = localIdentityProvider()
        val payload = JSONObject().put("id", local.deviceId).put("alias", local.alias)
            .put("sig", local.signingPublicKeyB64).put("kx", local.agreementPublicKeyB64)
            .put("fingerprint", local.fingerprint).toString()
        sendRaw(endpointId, packetSigner(MeshPacket(
            senderId = local.deviceId, destinationId = MeshPacket.BROADCAST_ID,
            packetType = PacketType.HELLO, encryptedPayload = payload, hopLimit = 1
        )))
    }

    private fun acceptHello(endpointId: String, packet: MeshPacket) {
        val data = runCatching { JSONObject(packet.encryptedPayload) }.getOrNull() ?: return
        val sig = data.optString("sig"); val agreement = data.optString("kx")
        val id = data.optString("id"); val fingerprint = data.optString("fingerprint")
        if (sig.isBlank() || agreement.isBlank() || id != packet.senderId) return
        if (!packetVerifier(packet, sig) || packet.integrityHash != MeshPacketCodec.computeIntegrityHash(packet)) return
        val expected = runCatching {
            MessageDigest.getInstance("SHA-256").digest("$sig|$agreement".toByteArray(Charsets.UTF_8))
                .take(16).joinToString(":") { "%02X".format(it) }
        }.getOrNull() ?: return
        if (fingerprint != expected || id != expected.replace(":", "").take(16).lowercase()) return
        val remote = Remote(id, data.optString("alias", "Nearby peer"), sig, agreement)
        identities[endpointId] = remote
        peerByEndpoint[endpointId] = id
        endpointByPeer[id] = endpointId
        scope.launch {
            eventFlow.emit(TransportEvent.PeerFound(DiscoveredEndpoint(
                deviceId = id, alias = remote.alias, signingPublicKey = sig, agreementPublicKey = agreement,
                transportType = transportType, address = endpointId, port = 0
            )))
            sendChallenge(endpointId)
        }
    }

    private suspend fun sendChallenge(endpointId: String) {
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val local = localIdentityProvider()
        val packet = packetSigner(MeshPacket(
            senderId = local.deviceId, destinationId = MeshPacket.BROADCAST_ID,
            packetType = PacketType.PEER_HANDSHAKE,
            encryptedPayload = JSONObject().put("phase", "challenge").put("nonce", nonce).toString(),
            hopLimit = 1
        ))
        challenges[endpointId] = Challenge(packet.messageId, nonce, System.currentTimeMillis())
        sendRaw(endpointId, packet)
    }

    private fun acceptProof(endpointId: String, packet: MeshPacket) {
        val remote = identities[endpointId] ?: return
        if (packet.senderId != remote.id || !packetVerifier(packet, remote.signing) ||
            packet.integrityHash != MeshPacketCodec.computeIntegrityHash(packet)) return
        val now = System.currentTimeMillis()
        seen.entries.removeIf { now - it.value > HANDSHAKE_TTL_MS }
        if (seen.putIfAbsent(packet.messageId, now) != null) return
        val data = runCatching { JSONObject(packet.encryptedPayload) }.getOrNull() ?: return
        val nonce = data.optString("nonce")
        if (!nonce.matches(Regex("[0-9a-f]{64}"))) return
        when (data.optString("phase")) {
            "challenge" -> scope.launch {
                val local = localIdentityProvider()
                sendRaw(endpointId, packetSigner(MeshPacket(
                    senderId = local.deviceId, destinationId = MeshPacket.BROADCAST_ID,
                    packetType = PacketType.PEER_HANDSHAKE,
                    encryptedPayload = JSONObject().put("phase", "response").put("nonce", nonce)
                        .put("challengeId", packet.messageId).toString(), hopLimit = 1
                )))
            }
            "response" -> {
                val expected = challenges[endpointId] ?: return
                if (now - expected.at !in 0..HANDSHAKE_TTL_MS ||
                    data.optString("challengeId") != expected.packetId || nonce != expected.nonce) return
                challenges.remove(endpointId, expected)
                if (authenticated.add(endpointId)) {
                    waiters.remove(endpointId)?.complete(true)
                    scope.launch {
                        eventFlow.emit(TransportEvent.ConnectionChanged(remote.id, transportType, true, endpointId))
                        eventFlow.emit(TransportEvent.Log(transportType, "MUTUAL_IDENTITY_PROOF_COMPLETE", "Nearby peer proved signing-key possession"))
                    }
                }
            }
        }
    }

    private suspend fun sendRaw(endpointId: String, packet: MeshPacket) {
        val bytes = MeshPacketCodec.encode(packet).toByteArray(Charsets.UTF_8)
        if (bytes.isNotEmpty() && bytes.size <= MAX_FRAME_BYTES) {
            runCatching { awaitCompletion(client.sendPayload(endpointId, Payload.fromBytes(bytes))) }
                .onFailure { log("HANDSHAKE_SEND_FAILED", it.message ?: "Nearby handshake send failed") }
        }
    }

    private fun log(type: String, message: String) {
        scope.launch { eventFlow.emit(TransportEvent.Log(transportType, type, message)) }
    }

    companion object {
        private const val SERVICE_ID = "com.example.meshlink.connections"
        private const val MAX_FRAME_BYTES = 1_000_000
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val HANDSHAKE_TTL_MS = 60_000L
    }
}

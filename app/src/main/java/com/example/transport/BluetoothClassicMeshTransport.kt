package com.example.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothManager
import android.content.Context
import com.example.core.crypto.LocalIdentityKeys
import com.example.core.permissions.HardwareCapabilities
import com.example.core.protocol.MeshPacket
import com.example.core.protocol.MeshPacketCodec
import com.example.core.protocol.TransportType
import org.json.JSONObject
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * RFCOMM transport for already-bonded peers. Pairing is intentionally delegated to
 * Android system UI; this class never silently pairs or makes the device discoverable.
 * Packets use a bounded 4-byte length prefix around MeshPacketCodec JSON.
 */
class BluetoothClassicMeshTransport(
    private val context: Context,
    private val localIdentityProvider: () -> LocalIdentityKeys,
    private val packetSigner: (MeshPacket) -> MeshPacket,
    private val packetVerifier: (MeshPacket, String) -> Boolean
) : MeshTransport {
    override val transportType = TransportType.BLUETOOTH_CLASSIC
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventsFlow = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    override val events: Flow<TransportEvent> = eventsFlow.asSharedFlow()
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val sockets = ConcurrentHashMap<String, BluetoothSocket>()
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile override var isRunning = false
        private set

    @SuppressLint("MissingPermission")
    override suspend fun start() = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        val bt = adapter
        if (bt == null || !bt.isEnabled || !HardwareCapabilities.hasBluetoothPermissions(context)) {
            eventsFlow.tryEmit(TransportEvent.Log(transportType, "CLASSIC_STANDBY",
                "Bluetooth Classic unavailable: enable Bluetooth and grant Nearby Devices permission"))
            return@withContext
        }
        isRunning = true
        server = runCatching { bt.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID) }
            .getOrElse {
                isRunning = false
                eventsFlow.tryEmit(TransportEvent.Log(transportType, "CLASSIC_SERVER_ERROR", it.message ?: "RFCOMM server failed"))
                return@withContext
            }
        scope.launch { acceptLoop() }
        triggerDiscovery()
    }

    @SuppressLint("MissingPermission")
    override suspend fun triggerDiscovery() {
        val bt = adapter ?: return
        if (!isRunning || !HardwareCapabilities.hasBluetoothPermissions(context)) return
        runCatching {
            bt.bondedDevices.orEmpty().forEach { device ->
                val endpoint = endpointFor(device)
                eventsFlow.emit(TransportEvent.PeerFound(endpoint))
            }
        }.onFailure {
            eventsFlow.emit(TransportEvent.Log(transportType, "CLASSIC_DISCOVERY_ERROR", it.message ?: "Bonded-device scan failed"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun endpointFor(device: BluetoothDevice): DiscoveredEndpoint {
        val address = device.address
        return DiscoveredEndpoint(
            deviceId = address.replace(":", "").lowercase(),
            alias = device.name?.takeIf { it.isNotBlank() } ?: "Bluetooth peer",
            signingPublicKey = "",
            agreementPublicKey = "",
            transportType = transportType,
            address = address,
            port = 0
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun acceptLoop() {
        while (isRunning) {
            val socket = try { server?.accept() ?: break } catch (_: Exception) { break }
            scope.launch { readSocket(socket, sendHello = true) }
        }
    }

    private suspend fun readSocket(socket: BluetoothSocket, sendHello: Boolean) = withContext(Dispatchers.IO) {
        val address = socket.remoteDevice?.address ?: return@withContext
        val peerId = address.replace(":", "").lowercase()
        sockets[peerId] = socket
        eventsFlow.emit(TransportEvent.ConnectionChanged(peerId, transportType, true, address))
        try {
            if (sendHello) sendIdentityHello(socket)
            val input = DataInputStream(socket.inputStream)
            while (isRunning && socket.isConnected) {
                val size = try { input.readInt() } catch (_: EOFException) { break }
                if (size !in 1..MAX_FRAME_BYTES) break
                val raw = ByteArray(size)
                input.readFully(raw)
                val packet = MeshPacketCodec.decode(String(raw, Charsets.UTF_8)) ?: continue
                if (packet.packetType == com.example.core.protocol.PacketType.HELLO) {
                    acceptIdentityHello(packet, socket)
                    continue
                }
                eventsFlow.emit(TransportEvent.PacketReceived(packet, transportType, address))
            }
        } catch (e: Exception) {
            eventsFlow.emit(TransportEvent.Log(transportType, "CLASSIC_RX_ERROR", e.message ?: "RFCOMM receive failed"))
        } finally {
            sockets.entries.removeAll { it.value === socket }
            runCatching { socket.close() }
            eventsFlow.emit(TransportEvent.ConnectionChanged(peerId, transportType, false, address))
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun sendPacket(endpoint: DiscoveredEndpoint, packet: MeshPacket): Boolean =
        withContext(Dispatchers.IO) {
            if (!isRunning || !HardwareCapabilities.hasBluetoothPermissions(context)) return@withContext false
            val bt = adapter ?: return@withContext false
            val socket = sockets[endpoint.deviceId]?.takeIf { it.isConnected }
                ?: runCatching {
                    bt.getRemoteDevice(endpoint.address)
                        .createRfcommSocketToServiceRecord(SERVICE_UUID)
                        .also {
                            it.connect()
                            sockets[endpoint.deviceId] = it
                            sendIdentityHello(it)
                            scope.launch { readSocket(it, sendHello = false) }
                        }
                }.getOrNull() ?: return@withContext false
            val bytes = MeshPacketCodec.encode(packet).toByteArray(Charsets.UTF_8)
            if (bytes.size !in 1..MAX_FRAME_BYTES) return@withContext false
            runCatching {
                val output = DataOutputStream(socket.outputStream)
                synchronized(output) {
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.flush()
                }
            }.isSuccess
        }

    private suspend fun sendIdentityHello(socket: BluetoothSocket) {
        val identity = localIdentityProvider()
        val payload = JSONObject().apply {
            put("id", identity.deviceId)
            put("alias", identity.alias)
            put("sig", identity.signingPublicKeyB64)
            put("kx", identity.agreementPublicKeyB64)
            put("fingerprint", identity.fingerprint)
        }.toString()
        val hello = packetSigner(
            MeshPacket(
                senderId = identity.deviceId,
                destinationId = MeshPacket.BROADCAST_ID,
                packetType = com.example.core.protocol.PacketType.HELLO,
                encryptedPayload = payload,
                hopLimit = 1
            )
        )
        val bytes = MeshPacketCodec.encode(hello).toByteArray(Charsets.UTF_8)
        if (bytes.size !in 1..MAX_FRAME_BYTES) return
        val output = DataOutputStream(socket.outputStream)
        synchronized(output) {
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }
    }

    private suspend fun acceptIdentityHello(packet: MeshPacket, socket: BluetoothSocket) {
        val address = socket.remoteDevice?.address ?: return
        val payload = runCatching { JSONObject(packet.encryptedPayload) }.getOrNull() ?: return
        val signingKey = payload.optString("sig")
        val agreementKey = payload.optString("kx")
        val alias = payload.optString("alias", "Bluetooth peer")
        val suppliedId = payload.optString("id")
        val fingerprint = payload.optString("fingerprint")
        if (signingKey.isBlank() || agreementKey.isBlank() || suppliedId != packet.senderId) return
        if (!packetVerifier(packet, signingKey)) return
        val expectedFingerprint = runCatching {
            val input = "$signingKey|$agreementKey".toByteArray(Charsets.UTF_8)
            MessageDigest.getInstance("SHA-256").digest(input).take(16)
                .joinToString(":") { "%02X".format(it) }
        }.getOrNull() ?: return
        val expectedId = expectedFingerprint.replace(":", "").take(16).lowercase()
        if (fingerprint != expectedFingerprint || suppliedId != expectedId) return
        sockets[expectedId] = socket
        eventsFlow.emit(
            TransportEvent.PeerFound(
                DiscoveredEndpoint(
                    deviceId = expectedId,
                    alias = alias,
                    signingPublicKey = signingKey,
                    agreementPublicKey = agreementKey,
                    transportType = transportType,
                    address = address,
                    port = 0
                )
            )
        )
        eventsFlow.emit(TransportEvent.Log(transportType, "IDENTITY_VERIFIED", "Authenticated MeshLink identity $expectedId"))
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        isRunning = false
        runCatching { server?.close() }
        server = null
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear()

    }

    companion object {
        private const val SERVICE_NAME = "MeshLink RFCOMM"
        private const val MAX_FRAME_BYTES = 256 * 1024
        private val SERVICE_UUID: UUID = UUID.fromString("7c6f2a10-8f4b-4c2d-9a71-4d4553484c4b")
    }
}

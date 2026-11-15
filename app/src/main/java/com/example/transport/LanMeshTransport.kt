package com.example.transport

import android.content.Context
import android.net.wifi.WifiManager
import com.example.core.crypto.LocalIdentityKeys
import com.example.core.permissions.HardwareCapabilities
import com.example.core.protocol.MeshPacket
import com.example.core.protocol.MeshPacketCodec
import com.example.core.protocol.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class LanMeshTransport(
    private val context: Context,
    private val localIdentityProvider: () -> LocalIdentityKeys
) : MeshTransport {

    override val transportType: TransportType = TransportType.LOCAL_LAN

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    @Volatile
    override var isRunning: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val jobs = mutableListOf<Job>()

    override suspend fun start() {
        if (isRunning) return
        isRunning = true

        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("MeshLinkLanLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        jobs += scope.launch { runTcpServer() }
        jobs += scope.launch { runUdpDiscoveryListener() }
        jobs += scope.launch {
            while (isActive && isRunning) {
                broadcastBeacon()
                delay(BEACON_INTERVAL_MS)
            }
        }

        _events.tryEmit(
            TransportEvent.Log(
                transportType = transportType,
                eventType = "DISCOVERY",
                message = "Local LAN UDP:$UDP_PORT / TCP:$TCP_PORT started (${HardwareCapabilities.resolveLocalIpv4() ?: "offline"})"
            )
        )
    }

    override suspend fun stop() {
        isRunning = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { serverSocket?.close() }
        runCatching { udpSocket?.close() }
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        serverSocket = null
        udpSocket = null
    }

    override suspend fun triggerDiscovery() {
        withContext(Dispatchers.IO) {
            broadcastBeacon()
        }
    }

    override suspend fun sendPacket(endpoint: DiscoveredEndpoint, packet: MeshPacket): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                val encoded = MeshPacketCodec.encode(packet).toByteArray(Charsets.UTF_8)
                if (encoded.size !in 1..MAX_FRAME_BYTES) return@withContext false
                Socket().use { socket ->
                    if (!isPrivateIpv4(endpoint.address)) return@withContext false
                    socket.connect(InetSocketAddress(endpoint.address, endpoint.port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = READ_TIMEOUT_MS
                    val out = DataOutputStream(socket.getOutputStream())
                    out.writeInt(encoded.size)
                    out.write(encoded)
                    out.flush()
                    DataInputStream(socket.getInputStream()).readUnsignedByte() == 1
                }
            }.onFailure { err ->
                _events.tryEmit(
                    TransportEvent.Log(
                        transportType = transportType,
                        eventType = "TX_ERR",
                        message = "LAN send failed to ${endpoint.address}: ${err.message}"
                    )
                )
            }.getOrDefault(false)
        }
    }

    private suspend fun runTcpServer() {
        runCatching {
            val srv = ServerSocket(TCP_PORT)
            serverSocket = srv
            while (isRunning) {
                val client = srv.accept()
                scope.launch { handleClientSocket(client) }
            }
        }.onFailure { err ->
            if (isRunning) {
                _events.tryEmit(
                    TransportEvent.Log(
                        transportType = transportType,
                        eventType = "SERVER_ERR",
                        message = "LAN TCP server stopped: ${err.message}"
                    )
                )
            }
        }
    }

    private suspend fun handleClientSocket(socket: Socket) {
        withContext(Dispatchers.IO) {
            runCatching {
                socket.use { s ->
                    s.soTimeout = READ_TIMEOUT_MS
                    if (!isPrivateIpv4(s.inetAddress.hostAddress.orEmpty()) || !isMeshLocalSocket(s)) {
                        DataOutputStream(s.getOutputStream()).apply { writeByte(0); flush() }
                        false
                    } else {
                        val input = DataInputStream(s.getInputStream())
                        val length = input.readInt()
                        var accepted = false
                        if (length in 1..MAX_FRAME_BYTES) {
                            val buf = ByteArray(length)
                            input.readFully(buf)
                            val raw = String(buf, Charsets.UTF_8)
                            val packet = MeshPacketCodec.decode(raw)
                            if (packet != null) {
                                _events.emit(
                                    TransportEvent.PacketReceived(
                                        packet = packet,
                                        transportType = transportType,
                                        remoteAddress = s.inetAddress.hostAddress ?: "lan"
                                    )
                                )
                                accepted = true
                            }
                        }
                        DataOutputStream(s.getOutputStream()).apply {
                            writeByte(if (accepted) 1 else 0)
                            flush()
                        }
                        accepted
                    }
                }
            }
        }
    }

    private suspend fun runUdpDiscoveryListener() {
        runCatching {
            val sock = DatagramSocket(UDP_PORT).apply { broadcast = true }
            udpSocket = sock
            val buf = ByteArray(4096)
            while (isRunning) {
                val dp = DatagramPacket(buf, buf.size)
                sock.receive(dp)
                val payload = String(dp.data, 0, dp.length, Charsets.UTF_8)
                val senderIp = dp.address?.hostAddress ?: continue
                if (!isPrivateIpv4(senderIp)) continue
                val localId = localIdentityProvider().deviceId
                val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                if (obj.optString("proto") != "MLP/1") continue
                val peerId = obj.optString("id")
                if (peerId.isBlank() || peerId == localId) continue
                val endpoint = DiscoveredEndpoint(
                    deviceId = peerId,
                    alias = obj.optString("alias", "Node-${peerId.take(6)}"),
                    signingPublicKey = obj.optString("sig", ""),
                    agreementPublicKey = obj.optString("kx", ""),
                    transportType = transportType,
                    address = senderIp,
                    port = obj.optInt("tcpPort", TCP_PORT),
                    rssiDbm = -52
                )
                _events.emit(TransportEvent.PeerFound(endpoint))
            }
        }
    }

    private fun isMeshLocalSocket(socket: Socket): Boolean {
        val interfaceName = runCatching {
            NetworkInterface.getByInetAddress(socket.localAddress)?.name
        }.getOrNull() ?: return false
        return MESH_INTERFACE_PREFIXES.any { interfaceName.startsWith(it, ignoreCase = true) }
    }

    private fun isPrivateIpv4(address: String): Boolean {
        val parts = address.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 169 && parts[1] == 254) ||
            parts[0] == 127
    }

    private fun broadcastBeacon() {
        runCatching {
            val id = localIdentityProvider()
            val beacon = JSONObject().apply {
                put("proto", "MLP/1")
                put("id", id.deviceId)
                put("alias", id.alias)
                put("sig", id.signingPublicKeyB64)
                put("kx", id.agreementPublicKeyB64)
                put("tcpPort", TCP_PORT)
            }.toString().toByteArray(Charsets.UTF_8)

            val interfaces = NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { iface ->
                    runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false) &&
                        MESH_INTERFACE_PREFIXES.any { iface.name.startsWith(it, ignoreCase = true) }
                }
                ?.flatMap { iface ->
                    iface.interfaceAddresses.asSequence().mapNotNull { item ->
                        val local = item.address
                        val broadcast = item.broadcast
                        if (local is Inet4Address && broadcast is Inet4Address && isPrivateIpv4(local.hostAddress.orEmpty())) {
                            local to broadcast
                        } else null
                    }
                }
                ?.toList().orEmpty()

            if (interfaces.isEmpty()) {
                DatagramSocket().use { sock ->
                    sock.broadcast = true
                    sock.send(
                        DatagramPacket(
                            beacon, beacon.size,
                            InetAddress.getByName("255.255.255.255"), UDP_PORT
                        )
                    )
                }
            } else {
                interfaces.forEach { (localAddress, broadcastAddress) ->
                    runCatching {
                        DatagramSocket(null).use { sock ->
                            sock.broadcast = true
                            sock.bind(InetSocketAddress(localAddress, 0))
                            sock.send(DatagramPacket(beacon, beacon.size, broadcastAddress, UDP_PORT))
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val UDP_PORT = 45820
        const val TCP_PORT = 45821
        private val MESH_INTERFACE_PREFIXES = listOf("wlan", "p2p", "ap", "swlan", "wifi")
        private const val BEACON_INTERVAL_MS = 8_000L
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_FRAME_BYTES = 2 * 1024 * 1024
    }
}

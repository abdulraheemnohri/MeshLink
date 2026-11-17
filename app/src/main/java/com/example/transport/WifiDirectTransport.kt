package com.example.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.core.permissions.HardwareCapabilities
import com.example.core.protocol.MeshPacket
import com.example.core.protocol.MeshPacketCodec
import com.example.core.protocol.TransportType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class WifiDirectTransport(
    private val context: Context
) : MeshTransport {

    override val transportType: TransportType = TransportType.WIFI_DIRECT

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    @Volatile
    override var isRunning: Boolean = false
        private set

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var groupOwnerAddress: String? = null
    @Volatile private var isGroupOwner: Boolean? = null
    @Volatile private var groupFormed: Boolean = false
    private var receiverRegistered = false
    private val peerIps = ConcurrentHashMap<String, String>()
    private var clientRefreshJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (HardwareCapabilities.hasNearbyWifiPermission(context)) {
                        manager?.requestPeers(channel) { peers ->
                            if (!isRunning) return@requestPeers
                            peers.deviceList.forEach { dev -> emitP2pDevice(dev) }
                            refreshGroupClients()
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    manager?.requestConnectionInfo(channel) { info ->
                        // Framework callbacks can arrive after stop() unregisters the receiver.
                        if (!isRunning) return@requestConnectionInfo
                        groupFormed = info.groupFormed
                        isGroupOwner = if (info.groupFormed) info.isGroupOwner else null
                        groupOwnerAddress = if (info.groupFormed) info.groupOwnerAddress?.hostAddress else null
                        if (!info.groupFormed) {
                            peerIps.clear()
                            clientRefreshJob?.cancel()
                            clientRefreshJob = null
                        }
                        if (info.groupFormed) {
                            refreshGroupClients()
                            startClientRefreshLoop()
                            _events.tryEmit(
                                TransportEvent.Log(
                                    transportType = TransportType.WIFI_DIRECT,
                                    eventType = "P2P_GROUP",
                                    message = "Wi-Fi Direct group formed (GO=${groupOwnerAddress ?: "self"})"
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        if (isRunning) return
        if (manager == null) return
        channel = channel ?: manager.initialize(context, Looper.getMainLooper(), null)
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        isRunning = true

        if (HardwareCapabilities.hasNearbyWifiPermission(context)) {
            triggerDiscovery()
        }
    }

    override suspend fun stop() {
        isRunning = false
        clientRefreshJob?.cancel()
        clientRefreshJob = null
        peerIps.clear()
        val activeChannel = channel
        if (activeChannel != null && HardwareCapabilities.hasNearbyWifiPermission(context)) {
            // Stop discovery and leave the P2P group when this adapter is disabled.
            // Keep the initialized Channel so a later start() can reuse it safely.
            runCatching {
                manager?.stopPeerDiscovery(activeChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) {
                        _events.tryEmit(TransportEvent.Log(
                            transportType, "P2P_STOP_DISCOVERY_ERR",
                            "Wi-Fi Direct discovery stop failed ($reason)"
                        ))
                    }
                })
            }
            runCatching {
                manager?.removeGroup(activeChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) {
                        _events.tryEmit(TransportEvent.Log(
                            transportType, "P2P_REMOVE_GROUP_ERR",
                            "Wi-Fi Direct group removal failed ($reason)"
                        ))
                    }
                })
            }
        }
        groupFormed = false
        isGroupOwner = null
        groupOwnerAddress = null
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun triggerDiscovery() {
        val ch = channel ?: return
        if (!HardwareCapabilities.hasNearbyWifiPermission(context)) return
        manager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _events.tryEmit(
                    TransportEvent.Log(
                        transportType = TransportType.WIFI_DIRECT,
                        eventType = "DISCOVERY",
                        message = "Wi-Fi Direct peer discovery triggered"
                    )
                )
            }

            override fun onFailure(reason: Int) {
                _events.tryEmit(
                    TransportEvent.Log(
                        transportType = TransportType.WIFI_DIRECT,
                        eventType = "P2P_ERR",
                        message = "Wi-Fi Direct discovery failed (reason $reason)"
                    )
                )
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connectToP2pPeer(deviceAddress: String) {
        val ch = channel ?: return
        if (!HardwareCapabilities.hasNearbyWifiPermission(context)) return
        val config = WifiP2pConfig().apply { this.deviceAddress = deviceAddress }
        manager?.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _events.tryEmit(
                    TransportEvent.Log(
                        transportType = TransportType.WIFI_DIRECT,
                        eventType = "CONNECT",
                        message = "Initiated Wi-Fi Direct handshake with $deviceAddress"
                    )
                )
            }

            override fun onFailure(reason: Int) {
                _events.tryEmit(
                    TransportEvent.Log(
                        transportType = TransportType.WIFI_DIRECT,
                        eventType = "P2P_ERR",
                        message = "Wi-Fi Direct connect failed ($reason)"
                    )
                )
            }
        })
    }

    override suspend fun sendPacket(endpoint: DiscoveredEndpoint, packet: MeshPacket): Boolean {
        if (!groupFormed) return false
        // Group clients send to the group owner. The owner sends to a client only after
        // that client's DHCP address has been observed in the local ARP table.
        val targetIp = if (isGroupOwner == true) {
            endpoint.address.takeIf { isValidIpv4(it) && it != groupOwnerAddress } ?: return false
        } else {
            groupOwnerAddress?.takeIf(::isValidIpv4) ?: return false
        }
        return withContext(Dispatchers.IO) {
            try {
                val encoded = MeshPacketCodec.encode(packet).toByteArray(Charsets.UTF_8)
                if (encoded.size !in 1..2 * 1024 * 1024) return@withContext false
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(targetIp, LanMeshTransport.TCP_PORT), 4_000)
                    socket.soTimeout = 10_000
                    val out = DataOutputStream(socket.getOutputStream())
                    out.writeInt(encoded.size)
                    out.write(encoded)
                    out.flush()
                    DataInputStream(socket.getInputStream()).readUnsignedByte() == 1
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _events.tryEmit(
                    TransportEvent.Log(
                        transportType = transportType,
                        eventType = "P2P_TX_ERR",
                        message = "Wi-Fi Direct send to $targetIp failed: ${failure.message}"
                    )
                )
                false
            }
        }
    }

    private fun startClientRefreshLoop() {
        if (isGroupOwner != true || clientRefreshJob?.isActive == true) return
        clientRefreshJob = scope.launch {
            while (isRunning && groupFormed && isGroupOwner == true) {
                refreshGroupClients()
                delay(CLIENT_REFRESH_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshGroupClients() {
        val ch = channel ?: return
        if (!HardwareCapabilities.hasNearbyWifiPermission(context) || isGroupOwner != true) return
        manager?.requestGroupInfo(ch) { group: WifiP2pGroup? ->
            val clients = group?.clientList ?: return@requestGroupInfo
            clients.forEach { device ->
                val peerId = device.deviceAddress.replace(":", "").lowercase()
                val ip = lookupArpAddress(device.deviceAddress)
                if (ip != null) {
                    peerIps[peerId] = ip
                    emitP2pDevice(device)
                }
            }
        }
    }

    private fun lookupArpAddress(macAddress: String): String? {
        val targetMac = macAddress.lowercase()
        return runCatching {
            File("/proc/net/arp").useLines { lines ->
                lines.drop(1).firstNotNullOfOrNull { line ->
                    val fields = line.trim().split(Regex("\\s+"))
                    if (fields.size >= 4 && fields[3].lowercase() == targetMac &&
                        fields[1] != "0x0" && isValidIpv4(fields[0])
                    ) fields[0] else null
                }
            }
        }.getOrNull()
    }

    private fun isValidIpv4(address: String): Boolean =
        address.matches(Regex("^(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$"))

    private fun emitP2pDevice(device: WifiP2pDevice) {
        val peerId = device.deviceAddress.replace(":", "").lowercase()
        val alias = device.deviceName.ifBlank { "P2P-${peerId.take(6)}" }
        scope.launch {
            _events.emit(
                TransportEvent.PeerFound(
                    DiscoveredEndpoint(
                        deviceId = peerId,
                        alias = alias,
                        signingPublicKey = "",
                        agreementPublicKey = "",
                        transportType = TransportType.WIFI_DIRECT,
                        // A P2P MAC address is not an IP address and must never be used as one.
                        address = if (isGroupOwner == true) peerIps[peerId].orEmpty() else groupOwnerAddress.orEmpty(),
                        port = LanMeshTransport.TCP_PORT,
                        rssiDbm = -55
                    )
                )
            )
        }
    }

    companion object {
        private const val CLIENT_REFRESH_INTERVAL_MS = 3_000L
    }
}

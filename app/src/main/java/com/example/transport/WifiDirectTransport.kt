package com.example.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.core.permissions.HardwareCapabilities
import com.example.core.protocol.MeshPacket
import com.example.core.protocol.MeshPacketCodec
import com.example.core.protocol.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

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
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (HardwareCapabilities.hasNearbyWifiPermission(context)) {
                        manager?.requestPeers(channel) { peers ->
                            peers.deviceList.forEach { dev ->
                                emitP2pDevice(dev)
                            }
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    manager?.requestConnectionInfo(channel) { info ->
                        if (info.groupFormed) {
                            groupOwnerAddress = info.groupOwnerAddress?.hostAddress
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
        channel = manager.initialize(context, Looper.getMainLooper(), null)
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
        val targetIp = groupOwnerAddress ?: endpoint.address
        if (!targetIp.contains('.')) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val encoded = MeshPacketCodec.encode(packet).toByteArray(Charsets.UTF_8)
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(targetIp, LanMeshTransport.TCP_PORT), 4_000)
                    val out = DataOutputStream(socket.getOutputStream())
                    out.writeInt(encoded.size)
                    out.write(encoded)
                    out.flush()
                }
                true
            }.getOrDefault(false)
        }
    }

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
                        address = groupOwnerAddress ?: device.deviceAddress,
                        port = LanMeshTransport.TCP_PORT,
                        rssiDbm = -55
                    )
                )
            )
        }
    }
}

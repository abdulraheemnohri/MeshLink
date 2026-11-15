package com.example.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.core.crypto.LocalIdentityKeys
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
import java.util.concurrent.ConcurrentHashMap

class WifiAwareTransport(
    private val context: Context,
    private val localIdentityProvider: () -> LocalIdentityKeys
) : MeshTransport {

    override val transportType: TransportType = TransportType.WIFI_AWARE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    @Volatile
    override var isRunning: Boolean = false
        private set

    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private val peerHandles = ConcurrentHashMap<String, PeerHandle>()

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        if (isRunning) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val awareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        if (awareManager == null || !awareManager.isAvailable || !HardwareCapabilities.hasNearbyWifiPermission(context)) {
            _events.tryEmit(
                TransportEvent.Log(
                    transportType = TransportType.WIFI_AWARE,
                    eventType = "AWARE_STANDBY",
                    message = "Wi-Fi Aware standby (unsupported or disabled on hardware)"
                )
            )
            return
        }

        isRunning = true
        val mainHandler = Handler(Looper.getMainLooper())
        awareManager.attach(object : AttachCallback() {
            override fun onAttached(awareSession: WifiAwareSession) {
                session = awareSession
                startPublishAndSubscribe(awareSession, mainHandler)
            }

            override fun onAttachFailed() {
                isRunning = false
                _events.tryEmit(
                    TransportEvent.Log(
                        transportType = TransportType.WIFI_AWARE,
                        eventType = "AWARE_ERR",
                        message = "Wi-Fi Aware attach failed"
                    )
                )
            }
        }, mainHandler)
    }

    @SuppressLint("MissingPermission")
    private fun startPublishAndSubscribe(awareSession: WifiAwareSession, handler: Handler) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val local = localIdentityProvider()
        val pubConfig = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(local.deviceId.toByteArray(Charsets.UTF_8))
            .build()

        awareSession.publish(pubConfig, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                publishSession = session
                _events.tryEmit(
                    TransportEvent.Log(
                        transportType = TransportType.WIFI_AWARE,
                        eventType = "DISCOVERY",
                        message = "Wi-Fi Aware publish active"
                    )
                )
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleIncomingBytes(peerHandle, message)
            }
        }, handler)

        val subConfig = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()

        awareSession.subscribe(subConfig, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                subscribeSession = session
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray,
                matchFilter: MutableList<ByteArray>
            ) {
                val peerId = String(serviceSpecificInfo, Charsets.UTF_8).ifBlank {
                    "aware_${peerHandle.hashCode()}"
                }
                peerHandles[peerId] = peerHandle
                scope.launch {
                    _events.emit(
                        TransportEvent.PeerFound(
                            DiscoveredEndpoint(
                                deviceId = peerId,
                                alias = "Aware-${peerId.take(6)}",
                                signingPublicKey = "",
                                agreementPublicKey = "",
                                transportType = TransportType.WIFI_AWARE,
                                address = peerId,
                                port = 0,
                                rssiDbm = -58
                            )
                        )
                    )
                }
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleIncomingBytes(peerHandle, message)
            }
        }, handler)
    }

    private fun handleIncomingBytes(peerHandle: PeerHandle, message: ByteArray) {
        val packet = MeshPacketCodec.decode(String(message, Charsets.UTF_8)) ?: return
        peerHandles[packet.senderId] = peerHandle
        scope.launch {
            _events.emit(
                TransportEvent.PacketReceived(
                    packet = packet,
                    transportType = TransportType.WIFI_AWARE,
                    remoteAddress = packet.senderId
                )
            )
        }
    }

    override suspend fun stop() {
        isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { publishSession?.close() }
            runCatching { subscribeSession?.close() }
            runCatching { session?.close() }
        }
        publishSession = null
        subscribeSession = null
        session = null
    }

    override suspend fun triggerDiscovery() {
        if (!isRunning) start()
    }

    override suspend fun sendPacket(endpoint: DiscoveredEndpoint, packet: MeshPacket): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val handle = peerHandles[endpoint.deviceId] ?: return false
        val sub = subscribeSession ?: return false
        return runCatching {
            val bytes = MeshPacketCodec.encode(packet).toByteArray(Charsets.UTF_8)
            sub.sendMessage(handle, packet.messageId.hashCode(), bytes)
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val SERVICE_NAME = "meshlink_aware"
    }
}

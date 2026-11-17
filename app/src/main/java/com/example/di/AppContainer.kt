package com.example.di

import android.content.Context
import com.example.core.crypto.MeshCryptoEngine
import com.example.data.database.AppDatabase
import com.example.data.repository.MeshRepository
import com.example.files.FileTransferManager
import com.example.location.LocationSharingManager
import com.example.routing.MeshRoutingEngine
import com.example.security.BiometricManager
import com.example.transport.BleMeshTransport
import com.example.transport.BluetoothClassicMeshTransport
import com.example.transport.LanMeshTransport
import com.example.transport.NearbyConnectionsMeshTransport
import com.example.transport.WifiAwareTransport
import com.example.transport.WifiDirectTransport
import com.example.transport.UniversalDiscoveryCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.example.voice.VoiceAudioManager
import kotlinx.coroutines.flow.MutableStateFlow

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val crypto = MeshCryptoEngine(appContext)
    val database = AppDatabase.getInstance(appContext)
    val repository = MeshRepository(database, crypto)
    val biometricManager = BiometricManager(appContext)

    val lanTransport = LanMeshTransport(appContext) { crypto.ensureLocalIdentity() }
    val bleTransport = BleMeshTransport(appContext) { crypto.ensureLocalIdentity() }
    val bluetoothClassicTransport = BluetoothClassicMeshTransport(
        appContext,
        { crypto.ensureLocalIdentity() },
        { packet -> crypto.signPacket(packet) },
        { packet, signingPublicKey -> crypto.verifyPacketSignature(packet, signingPublicKey) }
    )
    val wifiDirectTransport = WifiDirectTransport(appContext)
    val wifiAwareTransport = WifiAwareTransport(appContext) { crypto.ensureLocalIdentity() }
    val nearbyConnectionsTransport = NearbyConnectionsMeshTransport(
        appContext,
        { crypto.ensureLocalIdentity() },
        { packet -> crypto.signPacket(packet) },
        { packet, signingPublicKey -> crypto.verifyPacketSignature(packet, signingPublicKey) }
    )

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val discoveryCoordinator = UniversalDiscoveryCoordinator(
        listOf(lanTransport, wifiDirectTransport, wifiAwareTransport, bleTransport, bluetoothClassicTransport, nearbyConnectionsTransport),
        appScope
    )

    val routingEngine = MeshRoutingEngine(appContext, repository, crypto).apply {
        registerTransports(
            listOf(lanTransport, wifiDirectTransport, wifiAwareTransport, bleTransport, bluetoothClassicTransport)
        )
    }

    val fileTransferManager = FileTransferManager(appContext, repository, crypto, routingEngine)
    val voiceAudioManager = VoiceAudioManager(appContext, routingEngine)
    val locationSharingManager = LocationSharingManager(appContext)
    val transportPolicyManager = com.example.transport.TransportPolicyManager(appContext)

    val isRelayRunning = MutableStateFlow(false)
}

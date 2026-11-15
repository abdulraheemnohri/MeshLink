package com.example.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.example.core.crypto.LocalIdentityKeys
import com.example.core.permissions.HardwareCapabilities
import com.example.core.protocol.MeshPacket
import com.example.core.protocol.MeshPacketCodec
import com.example.core.protocol.BleFrameCodec
import com.example.core.protocol.TransportType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BleMeshTransport(
    private val context: Context,
    private val localIdentityProvider: () -> LocalIdentityKeys
) : MeshTransport {

    override val transportType: TransportType = TransportType.BLE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    @Volatile
    override var isRunning: Boolean = false
        private set

    private val btManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = btManager?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val incomingFrames = ConcurrentHashMap<String, BleFrameCodec.Reassembler>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val serviceData = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
            val deviceName = runCatching {
                if (HardwareCapabilities.hasBluetoothPermissions(context)) device.name else null
            }.getOrNull()

            val peerId = serviceData?.let { String(it, Charsets.UTF_8) }
                ?: device.address.replace(":", "").lowercase()
            val alias = deviceName ?: "BLE-${peerId.take(6)}"

            if (!discoveredDevices.containsKey(peerId) && discoveredDevices.size >= MAX_BLE_PEERS) {
                discoveredDevices.keys.firstOrNull()?.let(discoveredDevices::remove)
            }
            discoveredDevices[peerId] = device
            scope.launch {
                _events.emit(
                    TransportEvent.PeerFound(
                        DiscoveredEndpoint(
                            deviceId = peerId,
                            alias = alias,
                            signingPublicKey = "",
                            agreementPublicKey = "",
                            transportType = TransportType.BLE,
                            address = device.address,
                            port = 0,
                            rssiDbm = result.rssi
                        )
                    )
                )
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _events.tryEmit(
                TransportEvent.Log(
                    transportType = TransportType.BLE,
                    eventType = "DISCOVERY",
                    message = "BLE MeshLink beacon advertising active"
                )
            )
        }

        override fun onStartFailure(errorCode: Int) {
            _events.tryEmit(
                TransportEvent.Log(
                    transportType = TransportType.BLE,
                    eventType = "BLE_ADV_ERR",
                    message = "BLE advertising failed (code $errorCode)"
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        if (isRunning) return
        if (adapter == null || !adapter.isEnabled || !HardwareCapabilities.hasBluetoothPermissions(context)) {
            _events.tryEmit(
                TransportEvent.Log(
                    transportType = TransportType.BLE,
                    eventType = "BLE_STANDBY",
                    message = "BLE transport standby (Bluetooth off or permissions pending)"
                )
            )
            return
        }

        isRunning = true
        runCatching { startGattServer() }
        runCatching { startAdvertising() }
        runCatching { startScanning() }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stop() {
        isRunning = false
        incomingFrames.clear()
        discoveredDevices.clear()
        if (!HardwareCapabilities.hasBluetoothPermissions(context)) {
            runCatching { gattServer?.close() }
            gattServer = null
            return
        }
        runCatching { scanner?.stopScan(scanCallback) }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { gattServer?.close() }
        scanner = null
        advertiser = null
        gattServer = null
    }

    @SuppressLint("MissingPermission")
    override suspend fun triggerDiscovery() {
        if (!HardwareCapabilities.hasBluetoothPermissions(context) || adapter?.isEnabled != true) return
        runCatching {
            scanner?.stopScan(scanCallback)
            startScanning()
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun sendPacket(endpoint: DiscoveredEndpoint, packet: MeshPacket): Boolean {
        if (!HardwareCapabilities.hasBluetoothPermissions(context) || adapter?.isEnabled != true) return false
        val device = discoveredDevices[endpoint.deviceId] ?: runCatching {
            adapter?.getRemoteDevice(endpoint.address)
        }.getOrNull() ?: return false

        val payloadBytes = MeshPacketCodec.encode(packet).toByteArray(Charsets.UTF_8)
        val resultDeferred = CompletableDeferred<Boolean>()
        var frames: List<ByteArray> = emptyList()
        var nextFrame = 0
        var packetCharacteristic: BluetoothGattCharacteristic? = null
        var negotiatedMtu = 23

        val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    if (!resultDeferred.isCompleted) resultDeferred.complete(false)
                    g.close()
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!g.requestMtu(517)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!resultDeferred.isCompleted) resultDeferred.complete(false)
                    g.close()
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    resultDeferred.complete(false)
                    g.disconnect()
                    return
                }
                packetCharacteristic = g.getService(SERVICE_UUID)?.getCharacteristic(PACKET_CHAR_UUID)
                val char = packetCharacteristic
                if (char == null) {
                    resultDeferred.complete(false)
                    g.disconnect()
                    return
                }
                frames = runCatching {
                    BleFrameCodec.fragment(payloadBytes, negotiatedMtu - 3)
                }.getOrElse {
                    resultDeferred.complete(false)
                    g.disconnect()
                    return
                }
                nextFrame = 0
                writeNextFrame(g)
            }

            private fun writeNextFrame(g: BluetoothGatt) {
                val char = packetCharacteristic ?: run {
                    resultDeferred.complete(false)
                    g.disconnect()
                    return
                }
                if (nextFrame >= frames.size) {
                    resultDeferred.complete(true)
                    g.disconnect()
                    return
                }
                @Suppress("DEPRECATION")
                char.value = frames[nextFrame]
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                if (!g.writeCharacteristic(char)) {
                    resultDeferred.complete(false)
                    g.disconnect()
                }
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    resultDeferred.complete(false)
                    g.disconnect()
                    return
                }
                nextFrame += 1
                writeNextFrame(g)
            }
        })

        val delivered = withTimeoutOrNull(180_000L) { resultDeferred.await() } ?: false
        runCatching { gatt?.close() }
        return delivered
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        scanner = adapter?.bluetoothLeScanner
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (adapter?.isMultipleAdvertisementSupported != true) return
        advertiser = adapter.bluetoothLeAdvertiser ?: return
        val localId = localIdentityProvider().deviceId.take(12).toByteArray(Charsets.UTF_8)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), localId)
            .build()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        gattServer = btManager?.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    incomingFrames.remove(device.address)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                if (characteristic.uuid == PACKET_CHAR_UUID && offset == 0 && !preparedWrite) {
                    val reassembler = synchronized(incomingFrames) {
                        incomingFrames[device.address] ?: run {
                            if (incomingFrames.size >= MAX_BLE_PEERS) {
                                incomingFrames.keys.firstOrNull()?.let(incomingFrames::remove)
                            }
                            BleFrameCodec.Reassembler(transferTimeoutMs = 180_000L).also {
                                incomingFrames[device.address] = it
                            }
                        }
                    }
                    val completePayload = reassembler.accept(value)
                    if (completePayload != null) {
                        val decoded = MeshPacketCodec.decode(String(completePayload, Charsets.UTF_8))
                        if (decoded != null) {
                            scope.launch {
                                _events.emit(
                                    TransportEvent.PacketReceived(
                                        packet = decoded,
                                        transportType = TransportType.BLE,
                                        remoteAddress = device.address
                                    )
                                )
                            }
                        }
                    }
                }
            }
        })
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val packetChar = BluetoothGattCharacteristic(
            PACKET_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(packetChar)
        gattServer?.addService(service)
    }

    companion object {
        private const val MAX_BLE_PEERS = 64
        val SERVICE_UUID: UUID = UUID.fromString("7d2ea901-4f8b-4a3c-9c1e-8a6b5d4e3f01")
        val PACKET_CHAR_UUID: UUID = UUID.fromString("7d2ea902-4f8b-4a3c-9c1e-8a6b5d4e3f01")
    }
}

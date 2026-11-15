package com.meshlink.transport.wifi_direct

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.data.database.AppDatabase
import com.example.data.entities.PeerEntity
import com.example.core.permissions.HardwareCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MeshConnectionService : Service() {

    companion object {
        const val ACTION_START_DISCOVERY = "com.meshlink.action.START_DISCOVERY"
        const val ACTION_STOP_SERVICE = "com.meshlink.action.STOP_SERVICE"
        const val EXTRA_PEER_ADDRESS = "extra_peer_address"
        const val ACTION_CONNECT_PEER = "com.meshlink.action.CONNECT_PEER"
        
        private const val NOTIFICATION_CHANNEL_ID = "meshlink_p2p_channel"
        private const val NOTIFICATION_ID = 2002
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database by lazy { AppDatabase.getInstance(applicationContext) }

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = wifiP2pManager?.initialize(this, Looper.getMainLooper(), null)

        startForeground(NOTIFICATION_ID, createNotification("Initializing Wi-Fi Direct Mesh..."))
        registerP2pReceiver()
        startPeerDiscovery()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DISCOVERY -> startPeerDiscovery()
            ACTION_CONNECT_PEER -> {
                val address = intent.getStringExtra(EXTRA_PEER_ADDRESS)
                if (!address.isNullOrBlank()) {
                    connectToPeer(address)
                }
            }
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun createNotification(message: String): Notification {
        val channelName = "MeshLink P2P Service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(chan)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("MeshLink Wi-Fi Direct Mesh")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun registerP2pReceiver() {
        if (isReceiverRegistered) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        updateNotification(if (enabled) "Wi-Fi Direct Active" else "Wi-Fi Direct Disabled")
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        if (HardwareCapabilities.hasNearbyWifiPermission(applicationContext)) {
                            wifiP2pManager?.requestPeers(channel) { peerList ->
                                serviceScope.launch {
                                    val now = System.currentTimeMillis()
                                    peerList.deviceList.forEach { device ->
                                        val peerId = device.deviceAddress.replace(":", "").lowercase()
                                        val alias = device.deviceName.ifBlank { "P2P-${peerId.take(6)}" }
                                        val existing = database.peerDao().getPeerById(peerId)
                                        if (existing == null) {
                                            val newPeer = PeerEntity(
                                                deviceId = peerId,
                                                alias = alias,
                                                signingPublicKey = "",
                                                agreementPublicKey = "",
                                                fingerprint = peerId,
                                                isConnected = false,
                                                activeTransport = "WIFI_DIRECT",
                                                signalStrengthDbm = -50,
                                                lastAddress = device.deviceAddress,
                                                lastSeenAt = now
                                            )
                                            database.peerDao().upsert(newPeer)
                                        } else {
                                            database.peerDao().updateConnectionState(
                                                deviceId = peerId,
                                                connected = existing.isConnected,
                                                transport = "WIFI_DIRECT",
                                                seenAt = now
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        wifiP2pManager?.requestConnectionInfo(channel) { info ->
                            serviceScope.launch {
                                val now = System.currentTimeMillis()
                                if (info.groupFormed) {
                                    val goAddress = info.groupOwnerAddress?.hostAddress ?: "unknown"
                                    updateNotification("Connected (Group Owner: $goAddress)")
                                    val peers = database.peerDao().getUnblockedPeersOnce()
                                    peers.forEach { peer ->
                                        database.peerDao().updateConnectionState(
                                            deviceId = peer.deviceId,
                                            connected = true,
                                            transport = "WIFI_DIRECT",
                                            seenAt = now
                                        )
                                    }
                                } else {
                                    updateNotification("Searching for peers...")
                                    database.peerDao().markAllDisconnected()
                                }
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        isReceiverRegistered = true
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    private fun startPeerDiscovery() {
        val ch = channel ?: return
        if (!HardwareCapabilities.hasNearbyWifiPermission(this)) return
        wifiP2pManager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                updateNotification("Scanning for Wi-Fi Direct peers...")
            }
            override fun onFailure(reason: Int) {
                updateNotification("Discovery failed (code $reason)")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(deviceAddress: String) {
        val ch = channel ?: return
        if (!HardwareCapabilities.hasNearbyWifiPermission(this)) return
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }
        wifiP2pManager?.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                updateNotification("Connecting to $deviceAddress...")
            }
            override fun onFailure(reason: Int) {
                updateNotification("Connection failed ($reason)")
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered && receiver != null) {
            runCatching { unregisterReceiver(receiver) }
            isReceiverRegistered = false
        }
        serviceScope.launch {
            database.peerDao().markAllDisconnected()
        }
        serviceScope.cancel()
    }
}

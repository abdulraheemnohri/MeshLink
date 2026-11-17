package com.example.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.MeshLinkApplication
import com.example.di.AppContainer
import com.example.core.protocol.TransportType
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MeshRelayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startupJob: Job? = null
    private var maintenanceJob: Job? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRelayAndSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        val container = (application as MeshLinkApplication).container

        // START_STICKY may cause repeated start commands. Do not initialize radios twice.
        if (startupJob?.isActive != true && !container.isRelayRunning.value) {
            stopping = false
            startupJob = serviceScope.launch {
                val startup = RelayTaskGuard.run {
                    // A process may have died without onDestroy running. Treat all
                    // persisted radio states as stale, but keep the durable DTN outbox.
                    val expiredOnRecovery = container.repository.purgeExpiredCache()
                    container.repository.markAllDisconnected()
                    val queuedOnRecovery = container.repository.getActivePendingPackets().size
                    container.repository.logNetworkEvent(
                        transportType = "RELAY",
                        eventType = "PROCESS_RECOVERY",
                        summary = "Relay recovery reconciled stale connections; retained $queuedOnRecovery unexpired queued packet(s) and purged $expiredOnRecovery expired cache record(s)"
                    )

                    val settings = container.repository.getSettingsOnce()
                    container.repository.ensureInitialized()

                    val enabledTransports = buildSet {
                        if (settings.localLanEnabled) add(TransportType.LOCAL_LAN)
                        if (settings.bleEnabled) add(TransportType.BLE)
                        if (settings.bluetoothClassicEnabled) add(TransportType.BLUETOOTH_CLASSIC)
                        if (settings.nearbyConnectionsEnabled) add(TransportType.NEARBY_CONNECTIONS)
                        if (settings.wifiDirectEnabled) add(TransportType.WIFI_DIRECT)
                        if (settings.wifiAwareEnabled) add(TransportType.WIFI_AWARE)
                    }
                    // A failed adapter is isolated. The relay is considered started only
                    // when at least one enabled transport actually reports running.
                    container.discoveryCoordinator.start(enabledTransports)
                    container.discoveryCoordinator.discoverAll()
                    check(container.discoveryCoordinator.transportHealth.value.any { it.running }) {
                        "No enabled mesh transport could be started"
                    }

                    container.isRelayRunning.value = true
                    startMaintenance(container)
                }
                if (startup.isFailure) {
                    container.isRelayRunning.value = false
                    container.discoveryCoordinator.stopAll()
                    RelayTaskGuard.run {
                        container.repository.logNetworkEvent(
                            transportType = "RELAY",
                            eventType = "START_FAILED",
                            summary = startup.exceptionOrNull()?.message ?: "Relay startup failed"
                        )
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private fun startMaintenance(container: AppContainer) {
        if (maintenanceJob?.isActive == true) return
        maintenanceJob = serviceScope.launch {
            while (isActive && !stopping) {
                val flush = RelayTaskGuard.run { container.routingEngine.flushStoreAndForwardQueue() }
                val purge = RelayTaskGuard.run { container.repository.purgeExpiredCache() }
                if (flush.isFailure || purge.isFailure) {
                    RelayTaskGuard.run {
                        container.repository.logNetworkEvent(
                            transportType = "RELAY",
                            eventType = "MAINTENANCE_ERROR",
                            summary = listOfNotNull(
                                flush.exceptionOrNull()?.message,
                                purge.exceptionOrNull()?.message
                            ).joinToString("; ").take(500)
                        )
                    }
                }
                delay(MAINTENANCE_INTERVAL_MS)
            }
        }
    }

    private fun stopRelayAndSelf(startId: Int) {
        if (stopping) return
        stopping = true
        val container = (application as? MeshLinkApplication)?.container
        serviceScope.launch {
            startupJob?.cancelAndJoin()
            maintenanceJob?.cancelAndJoin()
            if (container != null) {
                stopTransports(container)
                container.isRelayRunning.value = false
                RelayTaskGuard.run { container.repository.markAllDisconnected() }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
    }

    override fun onDestroy() {
        stopping = true
        startupJob?.cancel()
        maintenanceJob?.cancel()
        val container = (application as? MeshLinkApplication)?.container
        serviceScope.cancel()

        // Cleanup must not be launched in the scope that is immediately cancelled.
        // A bounded independent IO scope lets radio adapters release their resources.
        if (container != null) {
            val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            cleanupScope.launch {
                withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
                    stopTransports(container)
                    container.isRelayRunning.value = false
                    RelayTaskGuard.run { container.repository.markAllDisconnected() }
                }
                cleanupScope.cancel()
            }
        }
        super.onDestroy()
    }

    private suspend fun stopTransports(container: AppContainer) {
        // The coordinator owns the registered adapter lifecycle, including adapters
        // that may be added later. Stop failures remain isolated per transport.
        container.discoveryCoordinator.stopAll()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.relay_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.relay_channel_desc)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.relay_notification_title))
            .setContentText(getString(R.string.relay_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "meshlink_relay_channel"
        private const val NOTIFICATION_ID = 4201
        private const val MAINTENANCE_INTERVAL_MS = 15_000L
        private const val CLEANUP_TIMEOUT_MS = 5_000L
        const val ACTION_START = "com.example.action.START_RELAY"
        const val ACTION_STOP = "com.example.action.STOP_RELAY"

        fun start(context: Context) {
            val intent = Intent(context, MeshRelayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshRelayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

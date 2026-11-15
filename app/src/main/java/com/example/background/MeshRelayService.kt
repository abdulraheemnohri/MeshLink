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
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
                runCatching {
                    val settings = container.repository.getSettingsOnce()
                    container.repository.ensureInitialized()

                    if (settings.localLanEnabled) runCatching { container.lanTransport.start() }
                    if (settings.bleEnabled) runCatching { container.bleTransport.start() }
                    if (settings.wifiDirectEnabled) runCatching { container.wifiDirectTransport.start() }
                    if (settings.wifiAwareEnabled) runCatching { container.wifiAwareTransport.start() }

                    container.isRelayRunning.value = true
                    startMaintenance(container)
                }.onFailure {
                    container.isRelayRunning.value = false
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
                runCatching { container.routingEngine.flushStoreAndForwardQueue() }
                runCatching { container.repository.purgeExpiredCache() }
                delay(MAINTENANCE_INTERVAL_MS)
            }
        }
    }

    private fun stopRelayAndSelf(startId: Int) {
        if (stopping) return
        stopping = true
        val container = (application as? MeshLinkApplication)?.container
        serviceScope.launch {
            startupJob?.cancel()
            maintenanceJob?.cancel()
            if (container != null) {
                stopTransports(container)
                container.isRelayRunning.value = false
                runCatching { container.repository.markAllDisconnected() }
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
                    runCatching { container.repository.markAllDisconnected() }
                }
                cleanupScope.cancel()
            }
        }
        super.onDestroy()
    }

    private suspend fun stopTransports(container: AppContainer) {
        runCatching { container.lanTransport.stop() }
        runCatching { container.bleTransport.stop() }
        runCatching { container.wifiDirectTransport.stop() }
        runCatching { container.wifiAwareTransport.stop() }
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

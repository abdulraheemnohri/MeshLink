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
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MeshRelayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var maintenanceJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRelayAndSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        val container = (application as MeshLinkApplication).container

        serviceScope.launch {
            val settings = container.repository.getSettingsOnce()
            container.repository.ensureInitialized()
            if (settings.localLanEnabled) container.lanTransport.start()
            if (settings.bleEnabled) container.bleTransport.start()
            if (settings.wifiDirectEnabled) container.wifiDirectTransport.start()
            if (settings.wifiAwareEnabled) container.wifiAwareTransport.start()
            container.isRelayRunning.value = true
        }

        maintenanceJob?.cancel()
        maintenanceJob = serviceScope.launch {
            while (isActive) {
                container.routingEngine.flushStoreAndForwardQueue()
                container.repository.purgeExpiredCache()
                delay(15_000L)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        maintenanceJob?.cancel()
        val container = (application as? MeshLinkApplication)?.container
        if (container != null) {
            container.isRelayRunning.value = false
            serviceScope.launch {
                container.lanTransport.stop()
                container.bleTransport.stop()
                container.wifiDirectTransport.stop()
                container.wifiAwareTransport.stop()
                container.repository.markAllDisconnected()
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopRelayAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

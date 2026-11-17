package com.example.files

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R

/** Best-effort transfer notifications; denied notification permission never breaks delivery. */
internal object FileTransferNotifications {
    private const val CHANNEL_ID = "meshlink_file_transfers"

    fun completed(context: Context, transferId: String, fileName: String, incoming: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "File transfers",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Verified MeshLink file transfer results" }
            )
        }
        val open = PendingIntent.getActivity(
            context,
            transferId.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val direction = if (incoming) "Received and verified" else "Delivered and verified"
        try {
            NotificationManagerCompat.from(context).notify(
                transferId.hashCode(),
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(direction)
                    .setContentText(fileName)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(fileName + " passed SHA-256 verification."))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: SecurityException) {
            // Android 13+ notification permission is optional for the transfer itself.
        }
    }
}

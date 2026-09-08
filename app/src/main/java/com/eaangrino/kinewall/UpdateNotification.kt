package com.eaangrino.kinewall

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

internal const val EXTRA_CHECK_FOR_UPDATES = "com.eaangrino.kinewall.CHECK_FOR_UPDATES"
internal const val EXTRA_INSTALL_AVAILABLE_UPDATE =
    "com.eaangrino.kinewall.INSTALL_AVAILABLE_UPDATE"

internal object UpdateNotification {
    private const val CHANNEL_ID = "kinewall_updates"
    private const val NOTIFICATION_ID = 2001

    fun show(context: Context, update: AvailableUpdate): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        createChannel(context)

        val openAppIntent = Intent(context, ComposeMainActivity::class.java).apply {
            putExtra(EXTRA_CHECK_FOR_UPDATES, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wallpaper_24)
            .setContentTitle(context.getString(R.string.update_available_title))
            .setContentText(
                context.getString(R.string.update_notification_message, update.version)
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return true
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.update_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(channel)
    }
}

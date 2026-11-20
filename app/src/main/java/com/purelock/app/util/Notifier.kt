package com.purelock.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.purelock.app.R
import com.purelock.app.ui.MainActivity

class Notifier(private val context: Context) {

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROTECTION, context.getString(R.string.channel_protection), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS, context.getString(R.string.channel_events), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** Ongoing notification required for the VPN foreground service. */
    fun vpnServiceNotification(): Notification =
        NotificationCompat.Builder(context, CH_PROTECTION)
            .setSmallIcon(R.drawable.ic_nav_shield)
            .setContentTitle(context.getString(R.string.notif_filter_title))
            .setContentText(context.getString(R.string.notif_filter_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    /** High-priority alert, e.g. protection was interrupted. */
    fun alert(id: Int, title: String, text: String) = notify(id, CH_ALERTS, title, text)

    /** Low-key information, e.g. "adult site blocked". */
    fun event(id: Int, title: String, text: String) = notify(id, CH_EVENTS, title, text)

    private fun notify(id: Int, channel: String, title: String, text: String) {
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_nav_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        try {
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) manager.notify(id, n)
        } catch (_: SecurityException) {
            // Notification permission was revoked between the check and the call: nothing to do.
        }
    }

    companion object {
        const val CH_PROTECTION = "protection"
        const val CH_ALERTS = "alerts"
        const val CH_EVENTS = "events"

        const val ID_VPN = 1001
        const val ID_INTEGRITY = 1002
        const val ID_UNLOCKED = 1003
        const val ID_WEB_BLOCK = 1004
        const val ID_ZONE = 1005
        const val ID_BOOT = 1006
    }
}

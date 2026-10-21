package com.eye3y3.finsync.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.eye3y3.finsync.R

object Notifications {
    const val CHANNEL = "sync"
    const val SYNC_ID = 1

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Syncing", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress while FinSync copies your music"
        })
    }

    /** The ongoing sync's notification; [stop] cancels the sync (the next one resumes where it stopped). */
    fun progress(context: Context, s: Status, stop: PendingIntent? = null): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (s.title.isEmpty()) "FinSync" else s.title)
            .setContentText(s.detail.ifEmpty { "Starting…" })
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .setProgress(s.total, s.done, s.total == 0 || s.done == 0)
        if (stop != null) b.addAction(0, "Stop", stop)
        return b.build()
    }
}

package com.eye3y3.finsync.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs a sync started from the app as a foreground service, so the long first sync carries on with
 * the screen off. Keeps the CPU and Wi-Fi awake while it runs and stops itself when done.
 */
class SyncService : LifecycleService() {
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            // From the notification's Stop: cancel cleanly; the job's finally stops the service.
            if (job?.isActive == true) job?.cancel() else stop()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, Notifications.SYNC_ID, Notifications.progress(this, SyncRunner.status.value, stopIntent()), type)
        if (job?.isActive == true) return START_NOT_STICKY
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FinSync:sync").apply { acquire(12 * 3600_000L) }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FinSync:sync").apply { acquire() }
        val updates = lifecycleScope.launch {
            SyncRunner.status.collect {
                if (it.running) try { NotificationManagerCompat.from(this@SyncService).notify(Notifications.SYNC_ID, Notifications.progress(this@SyncService, it, stopIntent())) } catch (e: SecurityException) {}
            }
        }
        job = lifecycleScope.launch {
            try {
                SyncRunner.sync(applicationContext)
            } catch (e: Exception) {
                // Shown on the home screen from SyncRunner.status.
            } finally {
                updates.cancel()
                stop()
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits data-sync services to 6 hours a day: stop cleanly; the next sync resumes. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        stop()
    }

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this, 1, Intent(this, SyncService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stop() {
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.eye3y3.finsync.STOP"

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
    }
}

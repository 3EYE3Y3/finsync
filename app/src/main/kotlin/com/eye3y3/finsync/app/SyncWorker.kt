package com.eye3y3.finsync.app

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eye3y3.finsync.jellyfin.FinSyncError
import java.util.concurrent.TimeUnit

/**
 * The scheduled sync: on Wi-Fi (unmetered) while charging, about twice a day. It asks to run as a
 * foreground job so long syncs aren't cut off; if Android doesn't allow that from the background, it
 * runs anyway and whatever is left is picked up next time (syncs resume).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = Settings(applicationContext)
        if (!s.ready || !s.setupDone) return Result.success()
        try { setForeground(getForegroundInfo()) } catch (e: Exception) { /* run as a normal job */ }
        return try {
            SyncRunner.sync(applicationContext)
            Result.success()
        } catch (e: FinSyncError.Busy) {
            Result.success()
        } catch (e: FinSyncError) {
            if (e.retryable) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val stop = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val n = Notifications.progress(applicationContext, SyncRunner.status.value, stop)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(Notifications.SYNC_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(Notifications.SYNC_ID, n)
    }

    companion object {
        private const val NAME = "scheduled-sync"

        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}

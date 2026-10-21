package com.eye3y3.finsync.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode

class FinSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds: network on the main thread crashes at once; disk access there is logged.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectNetwork().detectDiskReads().detectDiskWrites()
                    .penaltyLog().penaltyDeathOnNetwork().build(),
            )
        }
    }
}

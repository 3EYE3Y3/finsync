package com.eye3y3.finsync.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Google Play In-App Updates, flexible flow: when Play has a newer version, offer it; it downloads in the
 * background, then one tap restarts into it (not while a sync is running). Does nothing for builds that
 * weren't installed from Play.
 */
@Composable
internal fun UpdatePrompt(syncing: Boolean) {
    val ctx = LocalContext.current
    val manager = remember { AppUpdateManagerFactory.create(ctx) }
    var available by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != android.app.Activity.RESULT_OK) downloading = false
    }
    DisposableEffect(manager) {
        val listener = InstallStateUpdatedListener { st ->
            when (st.installStatus()) {
                InstallStatus.DOWNLOADED -> { ready = true; downloading = false }
                InstallStatus.FAILED, InstallStatus.CANCELED -> downloading = false
                else -> {}
            }
        }
        manager.registerListener(listener)
        onDispose { manager.unregisterListener(listener) }
    }
    LaunchedEffect(Unit) {
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus() == InstallStatus.DOWNLOADED) ready = true
            else if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) available = info
        }
    }
    when {
        ready -> Notice(
            if (syncing) "A new version of FinSync is ready. It installs once this sync has finished." else "A new version of FinSync is ready.",
            if (syncing) null else "Restart to update",
        ) { manager.completeUpdate() }
        downloading -> Notice("Downloading the new version of FinSync…", null) {}
        available != null -> Notice("A new version of FinSync is available.", "Update") {
            downloading = true
            manager.startUpdateFlowForResult(available!!, launcher, AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE))
            available = null
        }
    }
}

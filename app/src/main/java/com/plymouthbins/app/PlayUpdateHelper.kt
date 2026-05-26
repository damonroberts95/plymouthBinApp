package com.plymouthbins.app

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.plymouthbins.app.data.AppLog

/**
 * Wraps Play Core in-app updates. Use IMMEDIATE flow so the user must finish
 * updating before they keep using the app — same UX as the GitHub-distribution
 * banner that opens the APK download.
 *
 * Safe to instantiate on any device: on non-Play installs the API resolves to
 * UPDATE_NOT_AVAILABLE and the helper does nothing.
 */
class PlayUpdateHelper(private val activity: ComponentActivity) {

    private val mgr = AppUpdateManagerFactory.create(activity)

    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode != android.app.Activity.RESULT_OK) {
                AppLog.w("PlayUpdate: flow ended with resultCode=${result.resultCode}")
            }
        }

    /** Call from Activity.onResume(). */
    fun checkAndPrompt() {
        mgr.appUpdateInfo
            .addOnSuccessListener { info ->
                val resumeInProgress =
                    info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                val updateOffered =
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                            info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                if (resumeInProgress || updateOffered) {
                    runCatching {
                        mgr.startUpdateFlowForResult(
                            info,
                            launcher,
                            AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                        )
                    }.onFailure { AppLog.w("PlayUpdate: start flow failed (${it.message})") }
                }
            }
            .addOnFailureListener { AppLog.w("PlayUpdate: query failed (${it.message})") }
    }
}

package com.krstock.v3.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Downloads and validates a newer APK in the background.
 * Installation is intentionally deferred to the trusted Android package installer.
 */
class AppUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        AppAutoUpdater.checkAndDownload(applicationContext)
        Result.success()
    }.getOrElse {
        Result.retry()
    }
}

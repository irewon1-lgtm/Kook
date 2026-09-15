package com.krstock.v3.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Downloads and validates a newer APK in the background.
 * Installation is intentionally deferred to the trusted Android package installer.
 *
 * A missing release, temporary network failure, or rejected candidate is not a reason
 * for WorkManager's exponential retry loop: this is periodic maintenance, so the next
 * scheduled run will check again without wasting battery or network in the meantime.
 */
class AppUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching { AppAutoUpdater.checkAndDownload(applicationContext) }
        return Result.success()
    }
}

package com.krstock.v3

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.krstock.v3.data.update.SnapshotRefreshWorker
import com.krstock.v3.update.AppUpdateWorker
import java.util.concurrent.TimeUnit

class KR4Application : Application() {
    override fun onCreate() {
        super.onCreate()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val snapshotRequest = PeriodicWorkRequestBuilder<SnapshotRefreshWorker>(12, TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "kr4-validated-snapshot-refresh-v2",
            ExistingPeriodicWorkPolicy.UPDATE,
            snapshotRequest,
        )

        // Pre-download a validated newer APK in the background. Android itself
        // remains responsible for the final user-confirmed package installation.
        val appUpdateRequest = PeriodicWorkRequestBuilder<AppUpdateWorker>(12, TimeUnit.HOURS, 2, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "kr4-native-app-update-v1",
            ExistingPeriodicWorkPolicy.UPDATE,
            appUpdateRequest,
        )
    }
}

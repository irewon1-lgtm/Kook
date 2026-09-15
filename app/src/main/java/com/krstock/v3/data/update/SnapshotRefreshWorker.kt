package com.krstock.v3.data.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SnapshotRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val result = runCatching { SnapshotAutoUpdater.backgroundRefresh(applicationContext) }
            .getOrElse { return Result.retry() }
        return if (result.networkSucceeded) Result.success() else Result.retry()
    }
}

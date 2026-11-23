package com.tamir.hamasa.data.cloud

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Background push of all local data to Supabase. Used by both the periodic job and the one-time "push soon" job. */
class CloudSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ok = runCatching { CloudSync.pushAll(applicationContext) }.getOrDefault(false)
        return if (ok) Result.success() else if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

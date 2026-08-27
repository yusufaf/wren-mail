package dev.yusufaf.wren.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.yusufaf.wren.WrenApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Periodic background sync: flush queued triage ops, then refresh the cached
 * inbox. 15 minutes is WorkManager's periodic floor (and the right cadence for
 * watch battery anyway); no IMAP IDLE in v1.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as WrenApplication
        val account = app.accountStore.account.first() ?: return Result.success()
        return try {
            app.repository.refresh(account)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "inbox-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

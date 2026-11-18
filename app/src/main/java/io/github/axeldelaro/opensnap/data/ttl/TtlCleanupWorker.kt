package io.github.axeldelaro.opensnap.data.ttl

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.axeldelaro.opensnap.crypto.HardwareKeystoreManager
import java.util.concurrent.TimeUnit

/**
 * Periodic OS-scheduled fallback worker ensuring 24h cryptographic purge runs even if the user
 * force-stopped all processes and has not yet unlocked the phone.
 */
class TtlCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!HardwareKeystoreManager.hasActiveAccount(applicationContext)) {
            return Result.success()
        }
        return try {
            TtlPurgeEngine.purgeExpiredContent(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "opensnap_24h_ttl_cryptographic_scrubber"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TtlCleanupWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

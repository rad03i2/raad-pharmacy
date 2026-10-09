package com.radwan.raadpharmacy.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class CloudSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!CloudSyncScheduler.isEnabled(applicationContext)) return Result.success()
        var failed = false
        val operations: List<suspend () -> Unit> = listOf(
            { CloudSyncEngine(applicationContext).syncOnce() },
            { CloudPushDispatcher.retryPending(applicationContext) },
            { CloudNotificationInbox(applicationContext).catchUp() }
        )
        for (operation in operations) {
            if (!CloudSyncScheduler.isEnabled(applicationContext)) return Result.success()
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                failed = true
                FirebaseCrashlytics.getInstance().recordException(error)
            }
        }
        return if (failed) Result.retry() else Result.success()
    }

}

object CloudSyncScheduler {
    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences("raad_background_work", Context.MODE_PRIVATE)
        .getBoolean("enabled", false)

    fun enable(context: Context) {
        context.applicationContext.getSharedPreferences("raad_background_work", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", true).apply()
        ensurePeriodic(context)
        CloudPushRegistrationWorker.enqueue(context)
    }

    fun disable(context: Context) {
        CloudContinuousListening.stop(context)
        context.applicationContext.getSharedPreferences("raad_background_work", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", false).apply()
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork("raad-cloud-sync-now")
        manager.cancelUniqueWork("raad-cloud-network-catchup")
        manager.cancelUniqueWork("raad-cloud-sync-periodic")
        manager.cancelAllWorkByTag("raad-cloud-notifications")
        manager.cancelAllWorkByTag("raad-cloud-push-dispatch")
        manager.cancelUniqueWork("raad-cloud-push-registration")
    }

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(networkConstraint)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "raad-cloud-sync-now",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun ensureNetworkCatchUp(context: Context) {
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(networkConstraint)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "raad-cloud-network-catchup",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(
            15,
            TimeUnit.MINUTES
        )
            .setConstraints(networkConstraint)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            "raad-cloud-sync-periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}

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
import java.util.concurrent.TimeUnit

class CloudSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        runCatching {
            CloudSyncEngine(applicationContext).syncOnce()
            CloudNotificationInbox(applicationContext).catchUp()
            Result.success()
        }.getOrElse {
            FirebaseCrashlytics.getInstance().recordException(it)
            Result.retry()
        }
}

object CloudSyncScheduler {
    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(networkConstraint)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "raad-cloud-sync-now",
            ExistingWorkPolicy.REPLACE,
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

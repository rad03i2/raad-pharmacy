package com.radwan.raadpharmacy.cloud

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable data-only delivery, with no network constraint or server fetch. */
class CloudNotificationDeliveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val payload = inputData.getString("event") ?: return Result.failure()
        return runCatching {
            val row = Json.decodeFromString<CloudNotificationEventRow>(payload)
            CloudNotificationInbox(applicationContext).deliverPush(row)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        fun enqueue(context: Context, row: CloudNotificationEventRow) {
            val builder = OneTimeWorkRequestBuilder<CloudNotificationDeliveryWorker>()
                .setInputData(workDataOf("event" to Json.encodeToString(row)))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                "raad-notification-" + row.id, ExistingWorkPolicy.KEEP, builder.build()
            )
        }
    }
}

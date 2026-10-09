package com.radwan.raadpharmacy.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

/** Acknowledgement means local display was observed, never just FCM acceptance. */
class CloudNotificationReceiptWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!CloudSyncScheduler.isEnabled(applicationContext)) return Result.success()
        val eventId = inputData.getString("event_id") ?: return Result.failure()
        val recipient = inputData.getString("recipient") ?: return Result.failure()
        val store = CloudDeviceStore(applicationContext)
        if (store.pushUserId() != recipient) return Result.success()
        return try {
            val client = SupabaseProvider.client
            client.auth.awaitInitialization()
            if (client.auth.currentSessionOrNull()?.user?.id != recipient) return Result.retry()
            client.postgrest.rpc("acknowledge_notification", buildJsonObject {
                put("event", eventId)
                put("device", store.deviceId())
                put("displayed", true)
            })
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }

    companion object {
        fun enqueue(context: Context, eventId: String) {
            // Server events are UUIDs. Ignore malformed legacy/test payloads.
            if (!eventId.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))) return
            val recipient = CloudDeviceStore(context).pushUserId() ?: return
            val request = OneTimeWorkRequestBuilder<CloudNotificationReceiptWorker>()
                .setInputData(workDataOf("event_id" to eventId, "recipient" to recipient))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("raad-cloud-notifications")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "raad-push-receipt-$recipient-$eventId", ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** FCM may discard its offline data queue; the server event log remains authoritative. */
class CloudNotificationRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!CloudSyncScheduler.isEnabled(applicationContext)) return Result.success()
        return try {
            val client = SupabaseProvider.client
            client.auth.awaitInitialization()
            if (client.auth.currentSessionOrNull() == null) return Result.retry()
            if (CloudNotificationInbox(applicationContext).catchUp(forceRecovery = true)) Result.success()
            else Result.retry()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<CloudNotificationRecoveryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("raad-cloud-notifications")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "raad-push-queue-recovery", ExistingWorkPolicy.KEEP, request)
        }
    }
}

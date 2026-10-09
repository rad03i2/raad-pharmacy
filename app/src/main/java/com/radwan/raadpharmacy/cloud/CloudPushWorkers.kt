package com.radwan.raadpharmacy.cloud

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.TimeUnit

/** Sending survives the sender leaving the app; transient failures keep their work. */
class CloudPushDispatchWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!CloudSyncScheduler.isEnabled(applicationContext)) return Result.success()
        val transactionId = inputData.getString("transaction_id")
        val eventId = inputData.getString("event_id")
        if (transactionId == null && eventId == null) return Result.failure()
        return try {
            CloudPushDispatcher.dispatchNow(transactionId, eventId)
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }

    companion object {
        fun enqueue(context: Context, transactionId: String? = null, eventId: String? = null) {
            if (!CloudSyncScheduler.isEnabled(context)) return
            val id = eventId ?: transactionId ?: return
            val builder = OneTimeWorkRequestBuilder<CloudPushDispatchWorker>()
                .setInputData(workDataOf("transaction_id" to transactionId, "event_id" to eventId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("raad-cloud-push-dispatch")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            val request = builder.build()
            WorkManager.getInstance(context).enqueueUniqueWork("raad-push-dispatch-$id", ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Token registration runs independently from importing financial data. */
class CloudPushRegistrationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!CloudSyncScheduler.isEnabled(applicationContext)) return Result.success()
        return try {
            val token = suspendCancellableCoroutine<String> { continuation ->
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            }
            if (token.isBlank()) return Result.retry()
            CloudDeviceStore(applicationContext).saveFcmToken(token)
            if (CloudSyncScheduler.isEnabled(applicationContext)) CloudSyncEngine(applicationContext).refreshPushRegistration()
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }

    companion object {
        fun enqueue(context: Context) {
            if (!CloudSyncScheduler.isEnabled(context)) return
            val request = OneTimeWorkRequestBuilder<CloudPushRegistrationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("raad-cloud-push-registration", ExistingWorkPolicy.KEEP, request)
        }
    }
}

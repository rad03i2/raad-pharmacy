package com.radwan.raadpharmacy.cloud

import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.radwan.raadpharmacy.BuildConfig
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object CloudPushDispatcher {
    private val immediateDispatch = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { HttpClient(CIO) {
        expectSuccess = true
        install(HttpTimeout) { requestTimeoutMillis = 45_000L; connectTimeoutMillis = 10_000L }
    } }

    fun request(context: Context, transactionId: String) {
        // Persist the fallback BEFORE attempting immediate network dispatch.
        // Foreground and background running processes can send immediately,
        // while WorkManager survives process death, lost internet or FCM errors.
        CloudPushDispatchWorker.enqueue(context, transactionId = transactionId)
        immediateDispatch.launch {
            runCatching { withTimeout(10_000L) { dispatchNow(transactionId) } }
        }
    }

    fun requestEvent(context: Context, eventId: String) {
        CloudPushDispatchWorker.enqueue(context, eventId = eventId)
        immediateDispatch.launch {
            runCatching { withTimeout(10_000L) { dispatchNow(null, eventId) } }
        }
    }

    suspend fun retryPending(context: Context) {
        val app = context.applicationContext
        runCatching {
            val auth = SupabaseProvider.client.auth
            auth.awaitInitialization()
            val userId = auth.currentSessionOrNull()?.user?.id ?: return

            val pending = SupabaseProvider.client.from("notification_events")
                .select {
                    filter {
                        eq("actor_user_id", userId)
                        exact("push_dispatched_at", null)
                    }
                    order("created_at", Order.ASCENDING)
                    limit(MAX_RETRY_BATCH.toLong())
                }
                .decodeList<PendingPushRow>()

            for (event in pending) {
                try { dispatchNow(event.transactionId, event.id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    FirebaseCrashlytics.getInstance().recordException(error)
                    CloudPushDispatchWorker.enqueue(app, transactionId = event.transactionId, eventId = event.id)
                    break // A shared server/configuration failure must not retry every row at once.
                }
            }
        }.onFailure {
            if (it is CancellationException) throw it
            FirebaseCrashlytics.getInstance().recordException(it)
        }
    }

    internal suspend fun dispatchNow(transactionId: String?, eventId: String? = null) {
        val auth = SupabaseProvider.client.auth
        auth.awaitInitialization()
        val token = checkNotNull(auth.currentSessionOrNull()?.accessToken) { "Push session is not ready" }

        val response = http.post(
            BuildConfig.SUPABASE_URL +
                "/functions/v1/send-financial-notification"
        ) {
            header("Authorization", "Bearer $token")
            header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                transactionId?.let { put("transaction_id", it) }
                eventId?.let { put("event_id", it) }
            }.toString())
        }
        // 207 means some recipients failed; retain durable work for another attempt.
        check(response.status.value == 200) { "Push dispatch incomplete (${response.status.value})" }
    }

    @Serializable
    private data class PendingPushRow(
        val id: String,
        @SerialName("transaction_id") val transactionId: String? = null,
        @SerialName("push_attempts") val pushAttempts: Int = 0
    )

    private const val MAX_RETRY_BATCH = 20
}

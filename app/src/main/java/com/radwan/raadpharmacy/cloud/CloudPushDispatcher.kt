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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object CloudPushDispatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { HttpClient(CIO) {
        expectSuccess = true
        install(HttpTimeout) { requestTimeoutMillis = 10_000L; connectTimeoutMillis = 10_000L }
    } }

    fun request(context: Context, transactionId: String) {
        val app = context.applicationContext
        scope.launch {
            runCatching { dispatchNow(transactionId) }
                .onFailure { FirebaseCrashlytics.getInstance().recordException(it) }
        }
    }

    fun requestEvent(context: Context, eventId: String) {
        scope.launch {
            try { dispatchNow(null, eventId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { FirebaseCrashlytics.getInstance().recordException(error) }
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
                        lt("push_attempts", MAX_ATTEMPTS)
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
                    break // A shared server/configuration failure must not retry every row at once.
                }
            }
        }.onFailure {
            FirebaseCrashlytics.getInstance().recordException(it)
        }
    }

    private suspend fun dispatchNow(transactionId: String?, eventId: String? = null) {
        val auth = SupabaseProvider.client.auth
        auth.awaitInitialization()
        val token = auth.currentSessionOrNull()?.accessToken ?: return

        http.post(
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
    }

    @Serializable
    private data class PendingPushRow(
        val id: String,
        @SerialName("transaction_id") val transactionId: String? = null,
        @SerialName("push_attempts") val pushAttempts: Int = 0
    )

    private const val MAX_RETRY_BATCH = 20
    private const val MAX_ATTEMPTS = 10
}

package com.radwan.raadpharmacy.cloud

import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.radwan.raadpharmacy.BuildConfig
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object CloudPushDispatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { HttpClient(CIO) }

    fun request(context: Context, transactionId: String) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val auth = SupabaseProvider.client.auth
                auth.awaitInitialization()
                val token = auth.currentSessionOrNull()?.accessToken ?: return@runCatching

                http.post(
                    BuildConfig.SUPABASE_URL +
                        "/functions/v1/send-financial-notification"
                ) {
                    header("Authorization", "Bearer $token")
                    header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                    contentType(ContentType.Application.Json)
                    setBody("""{"transaction_id":"$transactionId"}""")
                }
            }.onFailure {
                FirebaseCrashlytics.getInstance().recordException(it)
            }
        }
    }
}

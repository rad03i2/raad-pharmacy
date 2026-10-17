package com.radwan.raadpharmacy.cloud

import com.radwan.raadpharmacy.BuildConfig
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.AuthConfig
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.http.encodedPath

object SupabaseProvider {
    private val clientDelegate = lazy {
        createCloudClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY)
    }
    val client: SupabaseClient get() = clientDelegate.value
    internal fun initializedClientOrNull(): SupabaseClient? = if (clientDelegate.isInitialized()) clientDelegate.value else null
}

/** Shared by production and real HTTP integration tests; no alternative auth or ledger protocol. */
@OptIn(SupabaseInternal::class)
internal fun createCloudClient(url: String, key: String, authConfig: AuthConfig.() -> Unit = {}): SupabaseClient =
        createSupabaseClient(supabaseUrl = url, supabaseKey = key) {
            install(Auth, authConfig)
            install(Postgrest) {
                requireValidSession = true
            }
            install(Realtime)
            install(Storage)
        }.also { client ->
            client.httpClient.httpClient.plugin(HttpSend).intercept { request ->
                request.headers[HttpHeaders.UserAgent] = "RaadPharmacy/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                try { execute(request) }
                catch (failure: Exception) {
                    failure.rethrowCloudCancellation()
                    // Capture the transport cause before supabase-kt replaces it with a
                    // HttpRequestException that does not retain the original throwable.
                    CloudConnectionDiagnostics.record(request.url.encodedPath, failure)
                    val code = CloudConnectionDiagnostics.classify(failure).code
                    if (code in setOf(CloudFailureCode.DNS, CloudFailureCode.TLS, CloudFailureCode.CONNECT,
                            CloudFailureCode.TIMEOUT, CloudFailureCode.TRANSPORT)) {
                        // Preserve a safe, request-specific code through the SDK's wrapper.
                        // Never infer one request's cause from a concurrent request's failure.
                        throw ClassifiedCloudTransportFailure(code, failure)
                    }
                    throw failure
                }
            }
        }

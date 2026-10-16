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
    val client by lazy {
        createCloudClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY)
    }
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
                    throw failure
                }
            }
        }

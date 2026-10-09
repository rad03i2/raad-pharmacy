package com.radwan.raadpharmacy.cloud

import android.content.Context
import com.radwan.raadpharmacy.BuildConfig
import com.radwan.raadpharmacy.data.PharmacyLedgerDatabase
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The live pharmacy is isolated from all pre-delivery demonstrations. */
internal object CloudHandover {
    const val REAL_EMAIL_DOMAIN = "@raad-pharmacy-live.local"
    const val LIVE_PHARMACY_ID = "3268adba-6375-4c04-8292-f7ae7ec9b490"
    fun isLiveAccount(email: String?): Boolean =
        email?.lowercase()?.endsWith(REAL_EMAIL_DOMAIN) == true
}

internal object CloudHandoverRegistration {
    private val client by lazy {
        HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 25_000L
                connectTimeoutMillis = 10_000L
            }
        }
    }

    suspend fun register(username: String, displayName: String, password: String, setupCode: String?): Boolean {
        val token = SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken
        val response = client.post(BuildConfig.SUPABASE_URL + "/functions/v1/handover-register") {
            header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            if (setupCode == null && token != null) header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("mode", if (setupCode == null) "add_user" else "setup")
                put("username", username)
                put("display_name", displayName)
                put("password", password)
                if (setupCode != null) put("setup_code", setupCode)
            }.toString())
        }
        return response.status.value == 201
    }
}

/** One-time isolation for in-place upgrades from the demonstration installation. */
internal object CloudHandoverLocalReset {
    private val mutex = Mutex()
    private const val STATE_PREFS = "raad_handover_v33181"

    suspend fun prepare(context: Context) = mutex.withLock {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("done", false)) return@withLock
        // Clear the *local* demonstration ledger BEFORE CloudSyncEngine.bootstrap
        // can upload its contents to the new production tenant.
        PharmacyLedgerDatabase.get(app).dao().replaceAll(emptyList(), emptyList())
        for (name in listOf(
            "raad_cloud_sync_journal",
            "raad_cloud_sync_state_v2",
            "raad_cloud_device",
            "raad_cloud_notification_inbox_v1",
            "raad_team_cache",
            "raad_team_message_cache",
            "raad_entry_actor_attribution_v1",
            "raad_cloud_auth",
            "raad_pharmacy_data",
            "gas_ledger_security",
            "customer_photo_store_v1"
        )) {
            check(app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()) {
                "Unable to clear old local state: $name"
            }
        }
        // Mark Room as initialized: do not resurrect an old legacy JSON ledger.
        check(app.getSharedPreferences("raad_pharmacy_data", Context.MODE_PRIVATE)
            .edit().putBoolean("room_initialized_v1", true).commit())
        for (dir in listOf("customer_photos", "auto_backups", "restore_recovery")) {
            java.io.File(app.filesDir, dir).deleteRecursively()
        }
        CloudTeamCache.get(app).clear()
        CloudTeamMessageCache.get(app).clear()
        check(prefs.edit().putBoolean("done", true).commit())
    }
}

package com.radwan.raadpharmacy.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The only v3.3.18.2 data change: remove local trial customers and ledger movements.
 * Keep login sessions, pharmacy users, permissions, app settings and device tokens.
 * Server-side trial records are tombstoned separately to prevent re-download.
 */
object LedgerReleaseCleanup {
    private const val PREF = "raad_release_33182"
    private val mutex = Mutex()

    suspend fun clearOnce(context: Context) = mutex.withLock {
        val app = context.applicationContext
        val marker = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (marker.getBoolean("ledger_cleared", false)) return@withLock

        PharmacyLedgerDatabase.get(app).dao().replaceAll(emptyList(), emptyList())

        // Old offline uploads must never recreate trial customers in Supabase.
        check(app.getSharedPreferences("raad_cloud_sync_journal", Context.MODE_PRIVATE)
            .edit().clear().commit())
        check(app.getSharedPreferences("raad_cloud_sync_state_v2", Context.MODE_PRIVATE)
            .edit().clear().commit())

        // Preserve automatic-backup preferences, but prevent JSON migration from
        // reintroducing customers removed above.
        val local = app.getSharedPreferences("raad_pharmacy_data", Context.MODE_PRIVATE)
        check(local.edit().remove("customers").remove("entries")
            .putBoolean("room_initialized_v1", true).commit())

        // Photos and attribution belonging to removed customer entries only.
        app.getSharedPreferences("customer_photo_store_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        app.getSharedPreferences("raad_entry_actor_attribution_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(app.filesDir, "customer_photos").deleteRecursively()

        // Old trial auto-backups are no longer valid for the clean ledger.
        File(app.filesDir, "auto_backups").deleteRecursively()
        File(app.filesDir, "restore_recovery").deleteRecursively()

        // No other preferences are reset (especially Supabase Auth, team, sound and UI).
        check(marker.edit().putBoolean("ledger_cleared", true).commit())
    }
}

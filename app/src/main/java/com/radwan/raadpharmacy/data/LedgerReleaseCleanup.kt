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

        // Cleanup was exclusive to 3.3.18.2. Later upgrades must preserve existing data,
        // including installations that skipped that release.
        check(marker.edit().putBoolean("ledger_cleared", true).commit())
    }
}

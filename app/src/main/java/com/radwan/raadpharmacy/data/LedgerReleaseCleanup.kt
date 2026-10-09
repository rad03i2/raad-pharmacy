package com.radwan.raadpharmacy.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Retires the 3.3.18.2 trial reset without erasing installations that skipped it. */
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

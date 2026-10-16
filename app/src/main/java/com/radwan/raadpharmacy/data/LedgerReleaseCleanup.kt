package com.radwan.raadpharmacy.data

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Retires the 3.3.18.2 trial reset without erasing installations that skipped it. */
object LedgerReleaseCleanup {
    private const val PREF = "raad_release_33182"
    private val mutex = Mutex()

    suspend fun retireOldMessagingTrial(context: Context) = mutex.withLock {
        val app = context.applicationContext
        val marker = app.getSharedPreferences("raad_retired_messaging_trial_3312210", Context.MODE_PRIVATE)
        if (marker.getBoolean("done", false)) return@withLock
        // Previous versions may have left periodic or one-shot background jobs.
        // Cancel them before removing their settings; never touch debt/ledger storage.
        val manager = WorkManager.getInstance(app)
        manager.cancelUniqueWork("raad-auto-whatsapp-daily")
        manager.cancelUniqueWork("raad-auto-whatsapp-check-now")
        check(app.deleteSharedPreferences("raad_auto_whatsapp_v1"))
        check(app.deleteSharedPreferences("raad_phone_whatsapp_trial_v1"))
        check(marker.edit().putBoolean("done", true).commit())
    }

    suspend fun clearOnce(context: Context) = mutex.withLock {
        val app = context.applicationContext
        val marker = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (marker.getBoolean("ledger_cleared", false)) return@withLock

        // Cleanup was exclusive to 3.3.18.2. Later upgrades must preserve existing data,
        // including installations that skipped that release.
        check(marker.edit().putBoolean("ledger_cleared", true).commit())
    }
}

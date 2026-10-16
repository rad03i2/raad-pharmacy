package com.radwan.raadpharmacy.cloud

import android.content.Context
import com.radwan.raadpharmacy.data.PharmacyLedgerDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** Application-owned retries continue while the user is working, without a refresh button. */
internal object CloudFinancialSyncRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var uploader: AutomaticFinancialUpload? = null

    @Synchronized fun start(context: Context): AutomaticFinancialUpload {
        uploader?.let { return it }
        val app = context.applicationContext
        val dao = PharmacyLedgerDatabase.get(app).dao()
        val pump = AutomaticFinancialUpload(scope,
            active = { CloudUiEvents.isAppForeground() && CloudSyncScheduler.isEnabled(app) },
            hasPending = { dao.restoreHold() != "1" && dao.cloudOutbox().isNotEmpty() },
            upload = { CloudSyncEngine(app).flushPendingOnly() },
            failed = { CloudMessagingFeedback.record("financial_upload", it) })
        uploader = pump
        scope.launch {
            // Recovers existing unsent amounts after process death or an app upgrade.
            dao.observeCloudPendingCount().collect { if (it > 0) pump.request() }
        }
        return pump
    }

    fun request(context: Context) { start(context).request() }
}

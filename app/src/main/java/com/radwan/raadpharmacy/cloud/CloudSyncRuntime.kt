package com.radwan.raadpharmacy.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.radwan.raadpharmacy.notifications.PixabaySoundAssets
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

object CloudSyncRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataRealtimeStarted = AtomicBoolean(false)
    private val notificationRealtimeStarted = AtomicBoolean(false)
    private val teamRealtimeStarted = AtomicBoolean(false)
    private val authObserverStarted = AtomicBoolean(false)
    private val presenceLoopStarted = AtomicBoolean(false)
    private val connectivityObserverStarted = AtomicBoolean(false)
    private val foregroundDeltaLoopStarted = AtomicBoolean(false)
    private val realtimeRecoveryRunning = AtomicBoolean(false)
    private val realtimeHealth = CloudRealtimeHealth()
    private val consecutiveRealtimeFailures = AtomicInteger(0)
    private val lastBackgroundAt = AtomicLong(0L)
    private val runtimeStarted = AtomicBoolean(false)
    private val catchUpRunning = Mutex()
    private val realtimeMutex = Mutex()
    private var realtimeJob: Job? = null
    private var realtimeOwner: String? = null
    private var lastSyncStartedAt = 0L

    fun start(context: Context) {
        val app = context.applicationContext
        CloudNotificationCenter.ensureChannels(app)
        CloudSyncScheduler.enable(app)
        CloudTeamRuntime.start(app)
        CustomerCreditStore.get(app).start()
        CloudFinancialSyncRuntime.start(app)
        com.radwan.raadpharmacy.backup.RecoveryScheduler.resume(app)
        if (!runtimeStarted.compareAndSet(false, true)) return
        CloudDeviceStore(app).refreshFcmToken(app)
        PixabaySoundAssets.prefetch(app)
        startPresenceLoop(app)
        startConnectivityObserver(app)
        startForegroundDeltaLoop(app)
        scope.launch { runCatching { CloudTeamMessageStore(app).refresh() }.onFailure { reportFailure(it) } }

        scope.launch {
            startRealtimeIfPossible(app)
            fullSyncAndCatchUp(app)
        }

        if (authObserverStarted.compareAndSet(false, true)) {
            scope.launch {
                val auth = SupabaseProvider.client.auth
                auth.awaitInitialization()
                auth.sessionStatus.collect { status ->
                    if (status is SessionStatus.Authenticated) {
                        scope.launch { runCatching { CloudTeamMessageStore(app).refresh() }.onFailure { reportFailure(it) } }
                        CloudSyncScheduler.enable(app)
                        CloudFinancialSyncRuntime.request(app)
                        startRealtimeIfPossible(app)
                        fullSyncAndCatchUp(app)
                        if (CloudUiEvents.isAppForeground()) {
                            runCatching { CloudTeamStore(app).heartbeat(true) }
                        }
                    }
                }
            }
        }
    }

    fun requestSync(context: Context) {
        val app = context.applicationContext
        CloudFinancialSyncRuntime.request(app)
        CloudSyncScheduler.enqueue(app)
    }

    suspend fun refreshNow(context: Context): Result<Unit> {
        val app = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                CloudSyncEngine(app).pullRemoteNow(forceSnapshot = true)
                CloudNotificationInbox(app).catchUp()
                startRealtimeIfPossible(app)
            }.onFailure { reportFailure(it) }
        }
    }

    fun onAppForegrounded(context: Context) {
        val app = context.applicationContext
        CloudUiEvents.setAppForeground(true)
        CustomerCreditStore.get(app).requestRefresh()
        com.radwan.raadpharmacy.notifications.ShortReminderScheduler.reconcile(app)
        CloudTeamRuntime.request(app)
        CloudFinancialSyncRuntime.request(app)
        CloudContinuousListening.startFromVisibleApp(app)
        val backgroundDuration = System.currentTimeMillis() - lastBackgroundAt.get()

        scope.launch {
            if (backgroundDuration >= REALTIME_RECONNECT_AFTER_MS) {
                resetRealtime()
            }

            startRealtimeIfPossible(app)
            runCatching { CloudTeamStore(app).heartbeat(true) }
            fullSyncAndCatchUp(app)
        }
    }

    fun onAppBackgrounded(context: Context? = null) {
        CloudUiEvents.setAppForeground(false)
        lastBackgroundAt.set(System.currentTimeMillis())
        context?.applicationContext?.let { app ->
            CloudSyncScheduler.enqueue(app)
            scope.launch { runCatching { CloudTeamStore(app).heartbeat(false) } }
        }
    }

    suspend fun signOut(context: Context) {
        val app = context.applicationContext
        CloudContinuousListening.stop(app)
        CloudSyncScheduler.disable(app)
        CloudDeviceStore(app).clearPushIdentity()
        runCatching { CloudTeamStore(app).heartbeat(false) }
        runCatching { CloudSyncEngine(app).unregisterPushToken() }
        resetRealtime()
        SupabaseProvider.client.auth.signOut()
        CloudDeviceRegistration.invalidate()
        CloudTeamCache.get(app).clear()
        CloudTeamMessageCache.get(app).clear()
        CloudDeviceStore(app).clearPushIdentity()
        CloudSyncScheduler.disable(app)
    }

    private suspend fun fullSyncAndCatchUp(context: Context) {
        // Lifecycle, auth and connectivity often arrive together. Share one catch-up.
        if (!catchUpRunning.tryLock()) return
        try {
            if (!CloudSyncScheduler.isEnabled(context)) return
            val now = SystemClock.elapsedRealtime()
            if (lastSyncStartedAt != 0L && now - lastSyncStartedAt < 5_000L) return
            lastSyncStartedAt = now
            runCatching { CloudSyncEngine(context).syncOnce() }.onFailure { reportFailure(it) }
            if (!CloudSyncScheduler.isEnabled(context)) return
            runCatching { CloudPushDispatcher.retryPending(context) }.onFailure { reportFailure(it) }
            runCatching { CloudNotificationInbox(context).catchUp() }.onFailure { reportFailure(it) }
            runCatching { CloudTeamMessageStore(context).refresh() }.onFailure { reportFailure(it) }
        } finally { catchUpRunning.unlock() }
    }

    private suspend fun resetRealtime() = realtimeMutex.withLock {
        realtimeJob?.cancel()
        realtimeJob = null
        realtimeOwner = null
        runCatching { SupabaseProvider.client.realtime.removeAllChannels() }
        realtimeHealth.reset()
        dataRealtimeStarted.set(false)
        notificationRealtimeStarted.set(false)
        teamRealtimeStarted.set(false)
    }

    /** Recover a lost ledger subscription instead of leaving dataRealtimeStarted stuck true.
     * Bounded retry delay avoids draining the battery when the internet is unavailable.
     */
    internal fun recoverLedgerRealtime(context: Context) {
        if (!realtimeRecoveryRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                val exponent = consecutiveRealtimeFailures.getAndIncrement().coerceAtMost(5)
                delay((2_000L * (1L shl exponent)).coerceAtMost(60_000L))
                if (CloudSyncScheduler.isEnabled(context) && realtimeHealth.needsRecovery(
                        dataRealtimeStarted.get(), notificationRealtimeStarted.get())) {
                    resetRealtime()
                    startRealtimeIfPossible(context)
                    // Release the recovery gate before slow ledger I/O, so a failed
                    // notification rejoin can schedule its next bounded retry.
                    scope.launch {
                        runCatching { CloudSyncEngine(context).pullRemoteNow() }
                            .onFailure { reportFailure(it) }
                    }
                }
            } finally {
                realtimeRecoveryRunning.set(false)
            }
        }
    }

    internal fun ledgerRealtimeConnected() {
        realtimeHealth.ledger.set(true)
        clearFailuresIfHealthy()
    }
    internal fun notificationRealtimeConnected() {
        realtimeHealth.messages.set(true)
        clearFailuresIfHealthy()
    }
    private fun clearFailuresIfHealthy() {
        if (!realtimeHealth.needsRecovery(dataRealtimeStarted.get(), notificationRealtimeStarted.get()))
            consecutiveRealtimeFailures.set(0)
    }
    internal fun ledgerRealtimeLost(context: Context) {
        realtimeHealth.ledger.set(false)
        recoverLedgerRealtime(context)
    }
    internal fun notificationRealtimeLost(context: Context) {
        realtimeHealth.messages.set(false)
        recoverLedgerRealtime(context)
    }

    /** Keep the alert subscription healthy; do not turn continuous monitoring into bulk sync. */
    internal suspend fun maintainNotificationListening(context: Context) {
        if (!CloudSyncScheduler.isEnabled(context)) return
        val auth = SupabaseProvider.client.auth
        auth.awaitInitialization()
        if (auth.currentSessionOrNull() == null) return
        startRealtimeIfPossible(context, includeLedger = false)
        CloudNotificationInbox(context).catchUp()
    }

    private suspend fun startRealtimeIfPossible(context: Context, includeLedger: Boolean = true): Unit = realtimeMutex.withLock {
        if (!CloudSyncScheduler.isEnabled(context)) return@withLock
        val owner = SupabaseProvider.client.auth.currentSessionOrNull()?.user?.id ?: return@withLock
        if (realtimeOwner != null && realtimeOwner != owner) {
            realtimeJob?.cancel()
            realtimeJob = null
            SupabaseProvider.client.realtime.removeAllChannels()
            realtimeHealth.reset()
            dataRealtimeStarted.set(false)
            notificationRealtimeStarted.set(false)
            teamRealtimeStarted.set(false)
        }
        realtimeOwner = owner
        val job = realtimeJob ?: SupervisorJob(scope.coroutineContext[Job]).also { realtimeJob = it }
        val realtimeScope = CoroutineScope(job + Dispatchers.IO)
        // Notification subscription is independent of financial uploads and screen lifetime.
        if (!notificationRealtimeStarted.get()) {
            val inbox = CloudNotificationInbox(context.applicationContext)
            if (inbox.startRealtime(realtimeScope)) notificationRealtimeStarted.set(true)
        }
        if (!teamRealtimeStarted.get()) {
            if (CloudTeamStore(context).startRealtime(realtimeScope)) teamRealtimeStarted.set(true)
        }
        if (includeLedger && !dataRealtimeStarted.get()) {
            val engine = CloudSyncEngine(context.applicationContext)
            if (engine.startRealtime(realtimeScope)) dataRealtimeStarted.set(true)
        }

    }

    private fun startConnectivityObserver(context: Context) {
        if (!connectivityObserverStarted.compareAndSet(false, true)) return
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    CloudTeamRuntime.request(context)
                    CloudFinancialSyncRuntime.request(context)
                    scope.launch {
                        resetRealtime()
                        startRealtimeIfPossible(context)
                        fullSyncAndCatchUp(context)
                    }
                }
            }
        )
    }

    /** Safety net for devices with degraded Realtime delivery: background-safe,
     * foreground-only delta refresh. It never replaces the normal push subscription.
     */
    private fun startForegroundDeltaLoop(context: Context) {
        if (!foregroundDeltaLoopStarted.compareAndSet(false, true)) return
        scope.launch {
            while (true) {
                delay(15_000L)
                if (CloudUiEvents.isAppForeground() && CloudSyncScheduler.isEnabled(context)
                    && !catchUpRunning.isLocked) {
                    runCatching { CloudSyncEngine(context).pullRemoteNow() }
                        .onFailure { reportFailure(it) }
                }
            }
        }
    }

    private fun startPresenceLoop(context: Context) {
        if (!presenceLoopStarted.compareAndSet(false, true)) return
        scope.launch {
            while (true) {
                if (CloudUiEvents.isAppForeground()) {
                    runCatching { CloudTeamStore(context).heartbeat(true) }
                }
                delay(PRESENCE_HEARTBEAT_MS)
            }
        }
    }

    private const val REALTIME_RECONNECT_AFTER_MS = 8_000L
    private const val PRESENCE_HEARTBEAT_MS = 25_000L

    private suspend fun reportFailure(error: Throwable) {
        error.rethrowCloudCancellation()
        CloudConnectionDiagnostics.record("runtime", error)
        FirebaseCrashlytics.getInstance().recordException(error)
    }
}

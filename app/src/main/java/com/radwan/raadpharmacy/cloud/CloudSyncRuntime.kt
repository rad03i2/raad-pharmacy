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

object CloudSyncRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataRealtimeStarted = AtomicBoolean(false)
    private val notificationRealtimeStarted = AtomicBoolean(false)
    private val authObserverStarted = AtomicBoolean(false)
    private val presenceLoopStarted = AtomicBoolean(false)
    private val connectivityObserverStarted = AtomicBoolean(false)
    private val fastSyncRunning = AtomicBoolean(false)
    private val fastSyncRequested = AtomicBoolean(false)
    private val lastBackgroundAt = AtomicLong(0L)
    private val runtimeStarted = AtomicBoolean(false)
    private val catchUpRunning = Mutex()
    private val realtimeMutex = Mutex()
    private var realtimeJob: Job? = null
    private var lastSyncStartedAt = 0L

    fun start(context: Context) {
        val app = context.applicationContext
        CloudNotificationCenter.ensureChannels(app)
        CloudSyncScheduler.enable(app)
        if (!runtimeStarted.compareAndSet(false, true)) return
        CloudDeviceStore(app).refreshFcmToken(app)
        PixabaySoundAssets.prefetch(app)
        startPresenceLoop(app)
        startConnectivityObserver(app)
        scope.launch { runCatching { CloudTeamMessageStore(app).refresh() }.onFailure { reportFailure(it) } }
        // Warm saved accounts independently of financial sync and the settings screen.
        scope.launch { runCatching { CloudTeamStore(app).load() }.onFailure { reportFailure(it) } }

        scope.launch {
            fullSyncAndCatchUp(app)
            startRealtimeIfPossible(app)
        }

        if (authObserverStarted.compareAndSet(false, true)) {
            scope.launch {
                val auth = SupabaseProvider.client.auth
                auth.awaitInitialization()
                auth.sessionStatus.collect { status ->
                    if (status is SessionStatus.Authenticated) {
                        scope.launch { runCatching { CloudTeamMessageStore(app).refresh() }.onFailure { reportFailure(it) } }
                        scope.launch { runCatching { CloudTeamStore(app).load() }.onFailure { reportFailure(it) } }
                        CloudSyncScheduler.enable(app)
                        fullSyncAndCatchUp(app)
                        startRealtimeIfPossible(app)
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
        CloudSyncScheduler.enqueue(app)
        fastSyncRequested.set(true)
        if (!fastSyncRunning.compareAndSet(false, true)) return

        scope.launch {
            try {
                do {
                    fastSyncRequested.set(false)
                    runCatching { CloudSyncEngine(app).flushPendingOnly() }
                        .onFailure { reportFailure(it) }
                    startRealtimeIfPossible(app)
                } while (fastSyncRequested.getAndSet(false))
            } finally {
                fastSyncRunning.set(false)
                if (fastSyncRequested.get()) requestSync(app)
            }
        }
    }

    suspend fun refreshNow(context: Context): Result<Unit> {
        val app = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                CloudSyncEngine(app).pullRemoteNow()
                CloudNotificationInbox(app).catchUp()
                startRealtimeIfPossible(app)
            }.onFailure { reportFailure(it) }
        }
    }

    fun onAppForegrounded(context: Context) {
        val app = context.applicationContext
        CloudUiEvents.setAppForeground(true)
        val backgroundDuration = System.currentTimeMillis() - lastBackgroundAt.get()

        scope.launch {
            if (backgroundDuration >= REALTIME_RECONNECT_AFTER_MS) {
                resetRealtime()
            }

            runCatching { CloudTeamStore(app).heartbeat(true) }
            fullSyncAndCatchUp(app)
            startRealtimeIfPossible(app)
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
        CloudSyncScheduler.disable(app)
        runCatching { CloudTeamStore(app).heartbeat(false) }
        runCatching { CloudSyncEngine(app).unregisterPushToken() }
        resetRealtime()
        SupabaseProvider.client.auth.signOut()
        CloudTeamCache.get(app).clear()
        CloudTeamMessageCache.get(app).clear()
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
        runCatching { SupabaseProvider.client.realtime.removeAllChannels() }
        dataRealtimeStarted.set(false)
        notificationRealtimeStarted.set(false)
    }

    private suspend fun startRealtimeIfPossible(context: Context): Unit = realtimeMutex.withLock {
        if (!CloudSyncScheduler.isEnabled(context)) return@withLock
        val job = realtimeJob ?: SupervisorJob(scope.coroutineContext[Job]).also { realtimeJob = it }
        val realtimeScope = CoroutineScope(job + Dispatchers.IO)
        if (!dataRealtimeStarted.get()) {
            val engine = CloudSyncEngine(context.applicationContext)
            if (engine.startRealtime(realtimeScope)) dataRealtimeStarted.set(true)
        }

        if (!notificationRealtimeStarted.get()) {
            val inbox = CloudNotificationInbox(context.applicationContext)
            if (inbox.startRealtime(realtimeScope)) notificationRealtimeStarted.set(true)
        }
    }

    private fun startConnectivityObserver(context: Context) {
        if (!connectivityObserverStarted.compareAndSet(false, true)) return
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        fullSyncAndCatchUp(context)
                        startRealtimeIfPossible(context)
                    }
                }
            }
        )
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

    private fun reportFailure(error: Throwable) {
        if (error is CancellationException) throw error
        FirebaseCrashlytics.getInstance().recordException(error)
    }
}

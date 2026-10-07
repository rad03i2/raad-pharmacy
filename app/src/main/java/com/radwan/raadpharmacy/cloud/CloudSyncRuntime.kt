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

    fun start(context: Context) {
        val app = context.applicationContext
        CloudNotificationCenter.ensureChannels(app)
        CloudSyncScheduler.ensurePeriodic(app)
        CloudSyncScheduler.ensureNetworkCatchUp(app)
        CloudDeviceStore(app).refreshFcmToken(app)
        PixabaySoundAssets.prefetch(app)
        startPresenceLoop(app)
        startConnectivityObserver(app)

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
                        .onFailure { FirebaseCrashlytics.getInstance().recordException(it) }
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
        return runCatching {
            CloudSyncEngine(app).pullRemoteNow()
            CloudNotificationInbox(app).catchUp()
            startRealtimeIfPossible(app)
        }.onFailure {
            FirebaseCrashlytics.getInstance().recordException(it)
        }
    }

    fun onAppForegrounded(context: Context) {
        val app = context.applicationContext
        CloudUiEvents.setAppForeground(true)
        val backgroundDuration = System.currentTimeMillis() - lastBackgroundAt.get()

        scope.launch {
            if (backgroundDuration >= REALTIME_RECONNECT_AFTER_MS) {
                runCatching { SupabaseProvider.client.realtime.removeAllChannels() }
                dataRealtimeStarted.set(false)
                notificationRealtimeStarted.set(false)
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
            scope.launch { runCatching { CloudTeamStore(app).heartbeat(false) } }
        }
    }

    suspend fun signOut(context: Context) {
        val app = context.applicationContext
        runCatching { CloudTeamStore(app).heartbeat(false) }
        runCatching { CloudSyncEngine(app).unregisterPushToken() }
        runCatching { SupabaseProvider.client.realtime.removeAllChannels() }
        dataRealtimeStarted.set(false)
        notificationRealtimeStarted.set(false)
        SupabaseProvider.client.auth.signOut()
    }

    private suspend fun fullSyncAndCatchUp(context: Context) {
        runCatching { CloudSyncEngine(context).syncOnce() }
            .onFailure { FirebaseCrashlytics.getInstance().recordException(it) }
        runCatching { CloudPushDispatcher.retryPending(context) }
            .onFailure { FirebaseCrashlytics.getInstance().recordException(it) }
        runCatching { CloudNotificationInbox(context).catchUp() }
            .onFailure { FirebaseCrashlytics.getInstance().recordException(it) }
    }

    private fun startRealtimeIfPossible(context: Context) {
        if (!dataRealtimeStarted.get()) {
            val engine = CloudSyncEngine(context.applicationContext)
            if (engine.startRealtime(scope)) dataRealtimeStarted.set(true)
        }

        if (!notificationRealtimeStarted.get()) {
            val inbox = CloudNotificationInbox(context.applicationContext)
            if (inbox.startRealtime(scope)) notificationRealtimeStarted.set(true)
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
}

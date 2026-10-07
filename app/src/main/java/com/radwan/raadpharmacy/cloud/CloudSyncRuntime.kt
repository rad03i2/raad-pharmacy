package com.radwan.raadpharmacy.cloud

import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object CloudSyncRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val realtimeStarted = AtomicBoolean(false)
    private val authObserverStarted = AtomicBoolean(false)
    private val lastBackgroundAt = AtomicLong(0L)

    fun start(context: Context) {
        val appContext = context.applicationContext
        CloudSyncScheduler.ensurePeriodic(appContext)
        CloudDeviceStore(appContext).refreshFcmToken(appContext)

        scope.launch {
            runCatching { CloudSyncEngine(appContext).syncOnce() }
                .onFailure { FirebaseCrashlytics.getInstance().recordException(it) }
            startRealtimeIfPossible(appContext)
        }

        if (authObserverStarted.compareAndSet(false, true)) {
            scope.launch {
                val auth = SupabaseProvider.client.auth
                auth.awaitInitialization()
                auth.sessionStatus.collect { status ->
                    if (status is SessionStatus.Authenticated) {
                        runCatching { CloudSyncEngine(appContext).syncOnce() }
                            .onFailure { FirebaseCrashlytics.getInstance().recordException(it) }
                        startRealtimeIfPossible(appContext)
                    }
                }
            }
        }
    }

    fun requestSync(context: Context) {
        val appContext = context.applicationContext
        CloudSyncScheduler.enqueue(appContext)
        scope.launch {
            runCatching { CloudSyncEngine(appContext).syncOnce() }
                .onFailure { FirebaseCrashlytics.getInstance().recordException(it) }
            startRealtimeIfPossible(appContext)
        }
    }

    suspend fun refreshNow(context: Context): Result<Unit> {
        val appContext = context.applicationContext
        return runCatching {
            CloudSyncEngine(appContext).syncOnce()
            startRealtimeIfPossible(appContext)
        }.onFailure {
            FirebaseCrashlytics.getInstance().recordException(it)
        }
    }

    fun onAppForegrounded(context: Context) {
        val appContext = context.applicationContext
        CloudUiEvents.setAppForeground(true)
        val backgroundDuration = System.currentTimeMillis() - lastBackgroundAt.get()

        scope.launch {
            if (backgroundDuration >= REALTIME_RECONNECT_AFTER_MS) {
                runCatching { SupabaseProvider.client.realtime.removeAllChannels() }
                realtimeStarted.set(false)
            }

            runCatching { CloudSyncEngine(appContext).syncOnce() }
                .onFailure { FirebaseCrashlytics.getInstance().recordException(it) }
            startRealtimeIfPossible(appContext)
        }
    }

    fun onAppBackgrounded() {
        CloudUiEvents.setAppForeground(false)
        lastBackgroundAt.set(System.currentTimeMillis())
    }

    suspend fun signOut(context: Context) {
        val appContext = context.applicationContext
        runCatching { CloudSyncEngine(appContext).unregisterPushToken() }
        runCatching { SupabaseProvider.client.realtime.removeAllChannels() }
        realtimeStarted.set(false)
        SupabaseProvider.client.auth.signOut()
    }

    private fun startRealtimeIfPossible(context: Context) {
        if (realtimeStarted.get()) return
        val engine = CloudSyncEngine(context.applicationContext)
        if (engine.startRealtime(scope)) {
            realtimeStarted.set(true)
        }
    }

    private const val REALTIME_RECONNECT_AFTER_MS = 5_000L
}

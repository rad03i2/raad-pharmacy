package com.radwan.raadpharmacy.cloud

import android.content.Context
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object CloudSyncRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val realtimeStarted = AtomicBoolean(false)
    private val authObserverStarted = AtomicBoolean(false)

    fun start(context: Context) {
        val appContext = context.applicationContext
        CloudSyncScheduler.ensurePeriodic(appContext)
        CloudDeviceStore(appContext).refreshFcmToken(appContext)

        scope.launch {
            runCatching { CloudSyncEngine(appContext).syncOnce() }
            startRealtimeIfPossible(appContext)
        }

        if (authObserverStarted.compareAndSet(false, true)) {
            scope.launch {
                val auth = SupabaseProvider.client.auth
                auth.awaitInitialization()
                auth.sessionStatus.collect { status ->
                    if (status is SessionStatus.Authenticated) {
                        runCatching { CloudSyncEngine(appContext).syncOnce() }
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
            startRealtimeIfPossible(appContext)
        }
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
}

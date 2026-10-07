package com.radwan.raadpharmacy.cloud

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object CloudSyncRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val realtimeStarted = AtomicBoolean(false)

    fun start(context: Context) {
        val appContext = context.applicationContext
        CloudSyncScheduler.ensurePeriodic(appContext)
        CloudDeviceStore(appContext).refreshFcmToken(appContext)

        scope.launch {
            val engine = CloudSyncEngine(appContext)
            runCatching { engine.syncOnce() }
            if (realtimeStarted.compareAndSet(false, true)) {
                engine.startRealtime(scope)
            }
        }
    }

    fun requestSync(context: Context) {
        val appContext = context.applicationContext
        CloudSyncScheduler.enqueue(appContext)
        scope.launch {
            runCatching { CloudSyncEngine(appContext).syncOnce() }
        }
    }
}

package com.radwan.raadpharmacy.cloud

import android.content.Context
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** Team loading belongs to the application, never to a settings screen or ledger sync lock. */
internal object CloudTeamRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refresh: AutomaticTeamRefresh? = null
    private val extrasMutex = Mutex()
    private var extras: Job? = null
    private val recovery = Mutex()
    private var lastRecovery = -5_000L

    @Synchronized fun start(context: Context): AutomaticTeamRefresh {
        refresh?.let { return it }
        val app = context.applicationContext
        val loader = AutomaticTeamRefresh(scope,
            active = { CloudUiEvents.isAppForeground() && CloudSyncScheduler.isEnabled(app) },
            refresh = { CloudTeamStore(app).load() },
            failed = { CloudMessagingFeedback.record("team_profiles", it) })
        refresh = loader
        scope.launch {
            SupabaseProvider.client.auth.sessionStatus.collect { status ->
                if (status is SessionStatus.Authenticated || status is SessionStatus.RefreshFailure) loader.request()
            }
        }
        loader.request()
        return loader
    }
    fun request(context: Context, recoverSession: Boolean = false) {
        val loader = start(context)
        loader.request()
        if (!recoverSession) return
        scope.launch {
            if (!recovery.tryLock()) return@launch
            try {
                val auth = SupabaseProvider.client.auth
                if (auth.sessionStatus.value is SessionStatus.RefreshFailure &&
                    SystemClock.elapsedRealtime() - lastRecovery >= 5_000L) {
                    lastRecovery = SystemClock.elapsedRealtime()
                    // Reload only the SDK's existing stored session; never create credentials.
                    withTimeout(5_000L) { auth.loadFromStorage() }
                    loader.request()
                }
            } catch (error: TimeoutCancellationException) { CloudMessagingFeedback.record("team_session_restore", error) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { CloudMessagingFeedback.record("team_session_restore", error) }
            finally { recovery.unlock() }
        }
    }

    fun enrich(context: Context, owner: String, generation: Long, profiles: List<CloudTeamProfileRow>) {
        val app = context.applicationContext
        scope.launch {
            extrasMutex.withLock {
                if (CloudTeamCache.get(app).generation != generation ||
                    SupabaseProvider.client.auth.currentSessionOrNull()?.user?.id != owner) return@withLock
                extras?.cancelAndJoin()
                extras = scope.launch { CloudTeamStore(app).enrich(owner, generation, profiles) }
            }
        }
    }
}

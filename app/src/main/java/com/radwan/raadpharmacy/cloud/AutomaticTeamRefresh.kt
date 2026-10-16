package com.radwan.raadpharmacy.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class TeamLoadingState { LOADING, WAITING_FOR_SESSION, RETRYING, READY }
internal class TeamSessionNotReady : IllegalStateException("TEAM_SESSION_NOT_READY")

/** One process-wide request at a time. Network/session events interrupt bounded backoff. */
internal class AutomaticTeamRefresh(scope: CoroutineScope, private val active: () -> Boolean,
    private val refresh: suspend () -> Unit, private val failed: (Throwable) -> Unit = {}) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(TeamLoadingState.LOADING)
    val state = mutableState.asStateFlow()
    init {
        scope.launch {
            for (signal in requests) {
                var failures = 0
                while (active()) {
                    mutableState.value = TeamLoadingState.LOADING
                    try {
                        refresh()
                        mutableState.value = TeamLoadingState.READY
                        break
                    } catch (error: Exception) {
                        if (error is CancellationException && error !is TimeoutCancellationException) throw error
                        mutableState.value = if (error is TeamSessionNotReady)
                            TeamLoadingState.WAITING_FOR_SESSION else TeamLoadingState.RETRYING
                        failed(error)
                    }
                    val delay = (1_000L * (1L shl failures.coerceAtMost(5))).coerceAtMost(30_000L)
                    failures++
                    withTimeoutOrNull(delay) { requests.receive() }
                }
            }
        }
    }
    fun request() { requests.trySend(Unit) }
}

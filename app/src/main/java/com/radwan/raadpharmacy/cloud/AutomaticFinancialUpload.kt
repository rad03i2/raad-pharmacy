package com.radwan.raadpharmacy.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class CloudSessionNotReady : IllegalStateException("CLOUD_SESSION_NOT_READY")
internal fun requireCloudSyncOwner(owner: String?): String = owner?.takeIf(String::isNotBlank) ?: throw CloudSessionNotReady()
internal enum class FinancialUploadState { IDLE, UPLOADING, WAITING_FOR_SESSION, WAITING_FOR_CREDIT_REVIEW, RETRYING }

/** The durable outbox is the source of truth. A missing session is never a successful upload. */
internal class AutomaticFinancialUpload(scope: CoroutineScope,
    private val active: () -> Boolean,
    private val hasPending: suspend () -> Boolean,
    private val upload: suspend () -> Unit,
    private val failed: (Throwable) -> Unit = {}) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(FinancialUploadState.IDLE)
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            for (signal in requests) {
                var failures = 0
                while (active() && hasPending()) {
                    mutableState.value = FinancialUploadState.UPLOADING
                    try {
                        upload()
                        // A write can commit during an upload. Drain its new revision too.
                        if (!hasPending()) { mutableState.value = FinancialUploadState.IDLE; break }
                        failures = 0
                    } catch (error: Exception) {
                        if (error is CancellationException && error !is TimeoutCancellationException) throw error
                        mutableState.value = when {
                            error is CloudSessionNotReady -> FinancialUploadState.WAITING_FOR_SESSION
                            error.isCreditBlockedFailure() -> FinancialUploadState.WAITING_FOR_CREDIT_REVIEW
                            else -> FinancialUploadState.RETRYING
                        }
                        failed(error)
                    }
                    val delay = (1_000L * (1L shl failures.coerceAtMost(5))).coerceAtMost(30_000L)
                    failures++
                    withTimeoutOrNull(delay) { requests.receive() }
                }
                if (!hasPending()) mutableState.value = FinancialUploadState.IDLE
            }
        }
    }

    fun request() { requests.trySend(Unit) }
}

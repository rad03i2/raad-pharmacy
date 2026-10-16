package com.radwan.raadpharmacy.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A request deadline is a failed attempt; stopping the parent still stops all work. */
internal suspend fun Throwable.rethrowCloudCancellation() {
    currentCoroutineContext().ensureActive()
    if (this is CancellationException && this !is TimeoutCancellationException) throw this
}

internal suspend fun recoverableCloudOperation(onFailure: (Throwable) -> Unit = {},
    operation: suspend () -> Unit): Boolean = try {
    operation()
    true
} catch (error: Exception) {
    error.rethrowCloudCancellation()
    onFailure(error)
    false
}

package com.radwan.raadpharmacy.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** An RPC UUID is the durable acceptance receipt. A lost response is reconciled by ID. */
internal suspend fun confirmedCloudSend(id: String, write: suspend () -> String, saved: suspend () -> Boolean) {
    try {
        check(write() == id) { "SEND_RECEIPT_INVALID" }
    } catch (failure: Exception) {
        if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
        val confirmed = try { saved() }
        catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
            false
        } catch (_: Exception) { false }
        if (!confirmed) throw failure
    }
}

package com.radwan.raadpharmacy.cloud

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class ConfirmedCloudSendTest {
    @Test fun receiptConfirmsSaveWithoutExtraFetch() = runTest {
        confirmedCloudSend("id", { "id" }, { error("Unnecessary reconciliation") })
    }
    @Test fun committedWriteWithLostResponseIsReconciledWithoutSecondInsert() = runTest {
        var writes = 0
        confirmedCloudSend("id", { writes++; throw IOException("lost response") }, { true })
        assertEquals(1, writes)
    }
    @Test fun wrongReceiptAndMissingEventNeverBecomeSuccess() = runTest {
        try {
            confirmedCloudSend("id", { "different-id" }, { false })
            fail("Missing durable receipt")
        } catch (failure: IllegalStateException) { assertEquals("SEND_RECEIPT_INVALID", failure.message) }
    }
    @Test fun unavailableReconciliationPreservesOriginalFailure() = runTest {
        val original = IOException("offline")
        try {
            confirmedCloudSend("id", { throw original }, { throw IOException("still offline") })
            fail("Expected original failure")
        } catch (failure: IOException) { assertSame(original, failure) }
    }
    @Test fun requestTimeoutAfterCommitStillChecksDurableEvent() = runTest {
        confirmedCloudSend("id", { withTimeout(1) { delay(10); "id" } }, { true })
    }
    @Test fun lifecycleCancellationNeverBecomesSuccess() = runTest {
        try {
            confirmedCloudSend("id", { throw CancellationException("cancelled") }, { error("Must not query") })
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
    }
    @Test fun retryUsesSameIdAndCreatesOnlyOneServerEvent() = runTest {
        val events = mutableSetOf<String>()
        var readable = false
        val write: suspend () -> String = { events.add("same-id"); throw IOException("lost") }
        try { confirmedCloudSend("same-id", write, { readable && "same-id" in events }); fail() }
        catch (_: IOException) { }
        readable = true
        confirmedCloudSend("same-id", write, { readable && "same-id" in events })
        assertEquals(setOf("same-id"), events)
    }
}

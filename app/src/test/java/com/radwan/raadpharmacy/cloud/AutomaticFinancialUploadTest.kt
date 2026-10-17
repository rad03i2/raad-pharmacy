package com.radwan.raadpharmacy.cloud

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AutomaticFinancialUploadTest {
    @Test fun missingSessionRetainsAmountAndAuthSignalUploadsWithoutAnotherWrite() = runTest {
        var owner: String? = null; var pending = true; var attempts = 0
        val pump = AutomaticFinancialUpload(backgroundScope, { true }, { pending }, {
            attempts++; requireCloudSyncOwner(owner); pending = false
        })
        pump.request(); runCurrent()
        assertTrue(pending); assertEquals(FinancialUploadState.WAITING_FOR_SESSION, pump.state.value)
        owner = "existing-user"; pump.request(); runCurrent()
        assertFalse(pending); assertEquals(2, attempts)
        assertEquals(FinancialUploadState.IDLE, pump.state.value)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun blockedFinancialOperationIsKeptAndIdentifiedAsRaadReviewInsteadOfNetworkFailure() = runTest {
        var blocked=true;var pending=true
        val pump=AutomaticFinancialUpload(backgroundScope,{true},{pending},{
            if(blocked)throw IllegalStateException("CUSTOMER_CREDIT_BLOCKED")
            pending=false
        })
        pump.request();runCurrent()
        assertTrue(pending);assertEquals(FinancialUploadState.WAITING_FOR_CREDIT_REVIEW,pump.state.value)
        blocked=false;pump.request();runCurrent()
        assertFalse(pending);assertEquals(FinancialUploadState.IDLE,pump.state.value)
    }
    @Test fun failedUploadRetriesWhileAppRemainsOpenAndStopsAfterConfirmation() = runTest {
        var pending = true; var attempts = 0
        val pump = AutomaticFinancialUpload(backgroundScope, { true }, { pending }, {
            if (++attempts < 3) throw IOException("offline")
            pending = false
        })
        pump.request(); runCurrent()
        advanceTimeBy(3_001); runCurrent()
        assertEquals(3, attempts); assertFalse(pending)
        advanceTimeBy(60_000); runCurrent(); assertEquals(3, attempts)
    }

    @Test fun networkSignalInterruptsBackoffAndManySignalsDoNotUploadConcurrently() = runTest {
        var pending = true; var online = false; var attempts = 0; var inFlight = 0; var maxFlight = 0
        val release = CompletableDeferred<Unit>()
        val pump = AutomaticFinancialUpload(backgroundScope, { true }, { pending }, {
            attempts++
            if (!online) throw IOException("offline")
            inFlight++; maxFlight = maxOf(maxFlight, inFlight)
            release.await(); pending = false; inFlight--
        })
        pump.request(); runCurrent(); advanceTimeBy(100)
        online = true; pump.request(); runCurrent()
        repeat(100) { pump.request() }; runCurrent()
        assertEquals(2, attempts); assertEquals(1, maxFlight)
        release.complete(Unit); runCurrent()
        assertEquals(2, attempts); assertEquals(FinancialUploadState.IDLE, pump.state.value)
    }

    @Test fun editsDuringAnUploadGetAFollowUpWithoutBeingMistakenForAcknowledgedWork() = runTest {
        var revision = 1; var confirmed = 0
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<Int>()
        val pump = AutomaticFinancialUpload(backgroundScope, { true }, { revision > confirmed }, {
            val sending = revision; sent += sending
            if (sending == 1) release.await()
            confirmed = sending
        })
        pump.request(); runCurrent()
        revision = 2; pump.request(); release.complete(Unit); runCurrent()
        assertEquals(listOf(1, 2), sent); assertEquals(2, confirmed)
    }

    @Test fun returningToForegroundResumesAnUnsentAmountAndBackgroundStopsFastRetry() = runTest {
        var active = true; var attempts = 0
        val pump = AutomaticFinancialUpload(backgroundScope, { active }, { true }, {
            attempts++; throw IOException("offline")
        })
        pump.request(); runCurrent(); active = false
        advanceTimeBy(60_000); runCurrent(); assertEquals(1, attempts)
        active = true; pump.request(); runCurrent(); assertEquals(2, attempts)
    }

    @Test fun networkTimeoutIsRetryableButParentCancellationDoesNotStartMoreUploads() = runTest {
        var attempts = 0
        val pump = AutomaticFinancialUpload(backgroundScope, { true }, { true }, {
            attempts++
            if (attempts == 1) withTimeout(100) { awaitCancellation() }
            throw CancellationException("runtime stopped")
        })
        pump.request(); runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(FinancialUploadState.RETRYING, pump.state.value)
        advanceTimeBy(60_000); runCurrent(); assertEquals(2, attempts)
    }

    @Test fun emptyOutboxDoesNotContactServerAndRepeatedFailuresHaveBoundedBackoff() = runTest {
        var pending = false; val times = mutableListOf<Long>()
        val pump = AutomaticFinancialUpload(backgroundScope, { true }, { pending }, {
            times += testScheduler.currentTime; throw IOException("offline")
        })
        pump.request(); runCurrent(); assertTrue(times.isEmpty())
        pending = true; pump.request(); runCurrent(); advanceTimeBy(61_001); runCurrent()
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 31_000L, 61_000L), times)
    }
}

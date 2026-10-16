package com.radwan.raadpharmacy.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CloudOperationGuardTest {
    @Test fun aRequestDeadlineKeepsTheObserverAliveAndAllowsTheNextAttempt() = runTest {
        var failures = 0; var continued = false
        val first = recoverableCloudOperation({ failures++ }) { withTimeout(100) { awaitCancellation() } }
        val second = recoverableCloudOperation { continued = true }
        assertFalse(first); assertTrue(second); assertTrue(continued); assertEquals(1, failures)
    }
    @Test fun aDeadlineOnTheParentStillStopsTheWorkerWithoutReportingSuccess() = runTest {
        var reported = false
        val result = runCatching { withTimeout(100) {
            recoverableCloudOperation({ reported = true }) { awaitCancellation() }
        } }
        assertTrue(result.exceptionOrNull() is CancellationException); assertFalse(reported)
    }
    @Test fun cancellationBySignOutOrWorkerStopNeverTurnsIntoRetry() = runTest {
        val result = runCatching { recoverableCloudOperation { throw CancellationException("stopped") } }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
    @Test fun aHealthyBacklogLongerThanThirtySecondsCompletesInsideTheActualLedgerGate() = runTest {
        var acknowledged = 0
        CloudSyncEngine.ledgerOperation { repeat(4) { delay(10_000); acknowledged++ } }
        assertEquals(4, acknowledged); assertEquals(40_000L, testScheduler.currentTime)
    }
}

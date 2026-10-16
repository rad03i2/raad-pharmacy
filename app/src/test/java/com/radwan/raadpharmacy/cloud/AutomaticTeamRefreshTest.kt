package com.radwan.raadpharmacy.cloud

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AutomaticTeamRefreshTest {
    @Test fun lateSessionDoesNotFinishAsAnEmptySuccessfulTeamAndAuthEventLoadsImmediately() = runTest {
        var sessionReady = false; var names = emptyList<String>(); var attempts = 0
        val loader = AutomaticTeamRefresh(backgroundScope, { true }, {
            attempts++
            if (!sessionReady) throw TeamSessionNotReady()
            names = listOf("رعد", "أحمد", "فؤاد")
        })
        loader.request(); runCurrent()
        assertEquals(TeamLoadingState.WAITING_FOR_SESSION, loader.state.value)
        assertTrue(names.isEmpty())
        sessionReady = true; loader.request(); runCurrent()
        assertEquals(TeamLoadingState.READY, loader.state.value)
        assertEquals(3, names.size); assertEquals(2, attempts)
        assertEquals(0L, testScheduler.currentTime) // no 30-second screen timer
    }

    @Test fun networkReturnInterruptsBackoffWithoutWaitingForManualRefresh() = runTest {
        var network = false; var attempts = 0
        val loader = AutomaticTeamRefresh(backgroundScope, { true }, {
            attempts++; if (!network) throw IOException("offline")
        })
        loader.request(); runCurrent()
        assertEquals(TeamLoadingState.RETRYING, loader.state.value)
        advanceTimeBy(100); network = true; loader.request(); runCurrent()
        assertEquals(TeamLoadingState.READY, loader.state.value)
        assertEquals(100L, testScheduler.currentTime); assertEquals(2, attempts)
    }

    @Test fun repeatedLifecycleEventsShareOneRequestAndOneFollowUp() = runTest {
        var concurrent = 0; var maximum = 0; var attempts = 0
        val release = CompletableDeferred<Unit>()
        val loader = AutomaticTeamRefresh(backgroundScope, { true }, {
            concurrent++; maximum = maxOf(maximum, concurrent); attempts++
            release.await(); concurrent--
        })
        loader.request(); runCurrent()
        repeat(100) { loader.request() }; runCurrent()
        assertEquals(1, attempts)
        release.complete(Unit); runCurrent()
        assertEquals(2, attempts); assertEquals(1, maximum)
    }

    @Test fun backgroundAndSignedOutStateDoNotKeepPollingAndNextOpenResumes() = runTest {
        var active = false; var attempts = 0
        val loader = AutomaticTeamRefresh(backgroundScope, { active }, { attempts++ })
        loader.request(); runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(0, attempts)
        active = true; loader.request(); runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, attempts) // success has no periodic polling loop
    }

    @Test fun failedAttemptsUseBoundedExponentialDelay() = runTest {
        val times = mutableListOf<Long>()
        val loader = AutomaticTeamRefresh(backgroundScope, { true }, {
            times += testScheduler.currentTime; throw IOException("offline")
        })
        loader.request(); runCurrent(); advanceTimeBy(61_001); runCurrent()
        assertEquals(listOf(0L,1_000L,3_000L,7_000L,15_000L,31_000L,61_000L), times)
    }

    @Test fun cancellationNeverBecomesReadyOrSchedulesAnotherAttempt() = runTest {
        var attempts = 0
        val loader = AutomaticTeamRefresh(backgroundScope, { true }, {
            attempts++; throw CancellationException("closed")
        })
        loader.request(); runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(1, attempts); assertNotEquals(TeamLoadingState.READY, loader.state.value)
    }
}

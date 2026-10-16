package com.radwan.raadpharmacy.cloud

import io.github.jan.supabase.realtime.RealtimeChannel.Status
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationConnectionMonitorTest {
    @Test fun startupDoesNotCauseReconnectStorm() = runTest {
        var snapshots = 0; var losses = 0
        flowOf(Status.UNSUBSCRIBED, Status.SUBSCRIBING).watchNotificationConnection({ snapshots++ }, { losses++ })
        assertEquals(0, snapshots); assertEquals(0, losses)
    }
    @Test fun lostConnectionTriggersRecoveryAndEveryRejoinCatchesUpOnce() = runTest {
        val actions = mutableListOf<String>()
        flowOf(Status.UNSUBSCRIBED, Status.SUBSCRIBING, Status.SUBSCRIBED, Status.SUBSCRIBED,
            Status.UNSUBSCRIBED, Status.SUBSCRIBING, Status.SUBSCRIBED)
            .watchNotificationConnection({ actions += "snapshot" }, { actions += "recover" })
        assertEquals(listOf("snapshot", "recover", "snapshot"), actions)
    }
}

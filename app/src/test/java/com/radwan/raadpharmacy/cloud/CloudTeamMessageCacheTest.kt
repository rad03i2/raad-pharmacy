package com.radwan.raadpharmacy.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudTeamMessageCacheTest {
    private lateinit var context: Context
    private fun row(id: String, sender: String = "sender", recipient: String = "recipient") =
        CloudNotificationEventRow(id, "pharmacy", actorUserId = sender, eventType = "TEAM_MESSAGE",
            recipientUserId = recipient, messageBody = "رسالة عربية", createdAt = "2026-10-09T04:00:00Z")
    @Before fun reset() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(CloudTeamMessageCache.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun onlyRecipientSeesMessagesAndRepeatedDeliveryDoesNotDuplicateThem() {
        val cache = CloudTeamMessageCache(context)
        cache.merge("recipient", listOf(row("one"), row("one"), row("other", recipient="third")))
        assertEquals(listOf("one"), cache.current("recipient").map { it.id })
        assertTrue(cache.current("sender").isEmpty()); assertTrue(cache.current(null).isEmpty())
        assertFalse(row("one").isAddressedTo("third")); assertFalse(row("one").isAddressedTo("sender"))
    }
    @Test fun unreadMessagesSurviveRestartAndAreGroupedByTheActualSender() {
        CloudTeamMessageCache(context).merge("recipient", listOf(row("one"), row("two", sender="second")))
        val cache = CloudTeamMessageCache(context); cache.restore("recipient")
        assertEquals(2, cache.current("recipient").groupBy { it.actorUserId }.size)
        assertEquals("رسالة عربية", cache.current("recipient").first().messageBody)
    }
    @Test fun readingOnlyDisplayedMessagesKeepsNewArrivalsUnreadAndSurvivesOfflineRestart() {
        val cache = CloudTeamMessageCache(context)
        cache.merge("recipient", listOf(row("one")))
        val displayed = cache.current("recipient").map { it.id }.toSet()
        cache.merge("recipient", listOf(row("two")))
        cache.markRead("recipient", displayed)
        val restarted = CloudTeamMessageCache(context); restarted.restore("recipient")
        assertEquals(listOf("two"), restarted.current("recipient").map { it.id })
        assertEquals(setOf("one"), restarted.pendingReads("recipient"))
        restarted.acknowledge("recipient", setOf("one"))
        restarted.merge("recipient", listOf(row("one"))) // A delayed push must not revive a read message.
        assertEquals(listOf("two"), restarted.current("recipient").map { it.id })
    }
    @Test fun serverRefreshRemovesMessagesReadOnAnotherDeviceAndPreservesInFlightArrivals() {
        val cache = CloudTeamMessageCache(context)
        cache.merge("recipient", listOf(row("old")), now=100)
        cache.merge("recipient", listOf(row("new")), now=300)
        cache.replace("recipient", emptyList(), startedAt=200)
        assertEquals(listOf("new"), cache.current("recipient").map { it.id })
    }
    @Test fun accountSwitchAndLogoutDoNotExposeThePreviousInbox() {
        val cache = CloudTeamMessageCache(context); cache.merge("recipient", listOf(row("one")))
        cache.restore("third"); assertTrue(cache.current("third").isEmpty())
        cache.restore("recipient"); assertEquals(1,cache.current("recipient").size)
        cache.clear()
        val restarted=CloudTeamMessageCache(context); restarted.restore("recipient")
        assertTrue(restarted.current("recipient").isEmpty())
    }
    @Test fun malformedReadAndNonMessageEventsNeverCreateTheRedDot() {
        val cache = CloudTeamMessageCache(context)
        cache.merge("recipient", listOf(row("read").copy(messageReadAt="2026-10-09T04:01:00Z"),
            row("blank").copy(messageBody=" "), row("financial").copy(eventType="DEBT_CREATED"),
            row("self",sender="recipient"), row("no-sender").copy(actorUserId=null)))
        assertTrue(cache.current("recipient").isEmpty())
    }
}

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
class CloudTeamCacheTest {
    private lateinit var context: Context
    private val now = 1_000_000L
    private val team = CloudTeamSnapshot(
        CloudTeamMember("one", "رعد", true, null, true, now),
        listOf(CloudTeamMember("two", "أحمد", false, null, true, now),
            CloudTeamMember("three", "فؤاد", false, null, false, now - 90_000L)))

    @Before fun reset() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(CloudTeamCache.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun allThreePeopleAreImmediatelyAvailableOnEverySettingsVisit() {
        val cache = CloudTeamCache(context)
        cache.publish("one", team)
        repeat(5) {
            val shown = cache.current("one", now)
            assertEquals("رعد", shown.current?.displayName)
            assertEquals(listOf("أحمد", "فؤاد"), shown.others.map { it.displayName })
        }
    }

    @Test fun savedPeopleSurviveProcessRestartWithoutAnyNetworkRequest() {
        CloudTeamCache(context).publish("one", team)
        val restarted = CloudTeamCache(context)
        assertNull(restarted.current("one", now).current)
        restarted.restore("one")
        assertEquals(team, restarted.current("one", now))
    }

    @Test fun anotherAccountAndAnUnauthenticatedSessionCannotSeeCachedPeople() {
        val cache = CloudTeamCache(context)
        cache.publish("one", team)
        assertNull(cache.current("two", now).current)
        assertTrue(cache.current("two", now).others.isEmpty())
        assertTrue(cache.current(null, now).others.isEmpty())
        val restarted = CloudTeamCache(context)
        restarted.restore("two")
        assertNull(restarted.current("two", now).current)
        assertTrue(restarted.current("two", now).others.isEmpty())
    }

    @Test fun cachedOnlineStateExpiresAndFutureTimestampsDoNotShowOnline() {
        val cache = CloudTeamCache(context)
        cache.publish("one", team)
        assertTrue(cache.current("one", now + 70_000L).current!!.isOnline)
        assertFalse(cache.current("one", now + 70_001L).current!!.isOnline)
        assertFalse(cache.current("one", now - 1L).current!!.isOnline)
        assertEquals(now, cache.current("one", now + 200_000L).current!!.lastSeenAt)
    }

    @Test fun signingOutRemovesMemoryAndDiskCopies() {
        val cache = CloudTeamCache(context)
        cache.publish("one", team)
        cache.clear()
        assertNull(cache.current("one", now).current)
        val restarted = CloudTeamCache(context)
        restarted.restore("one")
        assertTrue(restarted.current("one", now).others.isEmpty())
    }

    @Test fun refreshingReplacesNamesAndRemovesHiddenOrDeletedMembers() {
        val cache = CloudTeamCache(context)
        cache.publish("one", team)
        cache.publish("one", team.copy(others = listOf(team.others[0].copy(displayName = "أحمد الجديد"))))
        val restarted = CloudTeamCache(context)
        restarted.restore("one")
        assertEquals(listOf("أحمد الجديد"), restarted.current("one", now).others.map { it.displayName })
    }

    @Test fun damagedDiskCacheDoesNotBlockTheSettingsScreen() {
        context.getSharedPreferences(CloudTeamCache.PREFS, Context.MODE_PRIVATE).edit()
            .putString("team", "broken").commit()
        val cache = CloudTeamCache(context)
        cache.restore("one")
        assertNull(cache.current("one", now).current)
    }
}

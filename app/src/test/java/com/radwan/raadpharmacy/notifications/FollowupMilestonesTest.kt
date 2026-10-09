package com.radwan.raadpharmacy.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowupMilestonesTest {
    @Test fun aggregatesOnlyNewMilestonesWithoutCustomerDetails() {
        val items = listOf(
            FollowupMilestones.Milestone("a:100", 30),
            FollowupMilestones.Milestone("b:200", 30),
            FollowupMilestones.Milestone("c:300", 60),
            FollowupMilestones.Milestone("d:400", 90)
        )
        val (newItems, summary) = FollowupMilestones.pending(items) { 0 }
        assertEquals(4, newItems.size)
        assertEquals(2, summary.entered30)
        assertEquals(1, summary.reached60)
        assertEquals(1, summary.reached90)
        assertTrue(summary.message().contains("2 حسابًا"))
        assertFalse(summary.message().contains("a:100"))
    }

    @Test fun noRepeatOnceSameMilestoneWasDelivered() {
        val items = listOf(FollowupMilestones.Milestone("account:oldDebt", 30))
        val (pending, summary) = FollowupMilestones.pending(items) { 30 }
        assertTrue(pending.isEmpty())
        assertEquals(0, summary.total)
    }

    @Test fun escalationFrom30To60And90IsNotSuppressed() {
        val milestones = listOf(
            FollowupMilestones.Milestone("a:old", 60),
            FollowupMilestones.Milestone("b:old", 90))
        val (pending, summary) = FollowupMilestones.pending(milestones) { 30 }
        assertEquals(2, pending.size)
        assertEquals(0, summary.entered30)
        assertEquals(1, summary.reached60)
        assertEquals(1, summary.reached90)
    }

    @Test fun settlingOldestDebtMakesNewCycleIndependent() {
        val milestones = listOf(FollowupMilestones.Milestone("account:newDebt", 30))
        val (pending, _) = FollowupMilestones.pending(milestones) {
            if (it == "account:oldDebt") 90 else 0
        }
        assertEquals(1, pending.size)
    }
}

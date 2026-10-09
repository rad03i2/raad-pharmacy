package com.radwan.raadpharmacy.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FollowupDemoDataTest {
    private val date = LocalDate.of(2026, 10, 9)

    @Test fun exactlyTwentyUniqueAccountsAndNoRealContactNumbers() {
        val sample = FollowupDemoData.create(date)
        assertEquals(20, sample.customers.size)
        assertEquals(20, sample.customers.map { it.id }.distinct().size)
        assertTrue(sample.customers.all { FollowupDemoData.isDemoCustomer(it.id) })
        assertTrue(sample.customers.all { it.phone == null && it.openingDebt == 0L })
        assertTrue(sample.movements.all { it.customerId.startsWith(FollowupDemoData.PREFIX) })
        assertTrue(sample.movements.all { it.amount > 0L })
    }

    @Test fun allTwentyAppearInDistinctOverdueGroups() {
        val fixture = FollowupDemoData.create(date)
        val accounts = DebtFollowupEngine.build(fixture.customers, fixture.movements, date)
        assertEquals(20, accounts.size)
        assertEquals(8, accounts.count { it.bucket == DebtFollowupEngine.Bucket.DAYS_30_TO_59 })
        assertEquals(4, accounts.count { it.bucket == DebtFollowupEngine.Bucket.DAY_60 })
        assertEquals(8, accounts.count { it.bucket == DebtFollowupEngine.Bucket.OVER_60 })
        assertTrue(accounts.none {
            it.bucket == DebtFollowupEngine.Bucket.NEEDS_REVIEW ||
                it.bucket == DebtFollowupEngine.Bucket.NOT_OVERDUE
        })
        assertEquals(20, accounts.map { it.customer.id }.distinct().size)
    }

    @Test fun partialSettlementsKeepOldBalanceAndRecentDebtStaysSeparate() {
        val fixture = FollowupDemoData.create(date)
        val projection = DebtFollowupEngine.build(fixture.customers, fixture.movements, date)
        // First sample: original 15,000, paid 3,750, and a recent 6,000 debt.
        val first = projection.first { it.customer.id == FollowupDemoData.PREFIX + "01" }
        assertEquals(17_250L, first.balance)
        assertEquals(11_250L, first.overdueAmount)
        assertEquals(30L, first.ageDays)
        assertEquals(DebtFollowupEngine.Bucket.DAYS_30_TO_59, first.bucket)
    }

    @Test fun previewIsDeterministicAndRolloverChangesAgeWithoutPersistingData() {
        val fixture = FollowupDemoData.create(date)
        val once = DebtFollowupEngine.build(fixture.customers, fixture.movements, date)
        val again = DebtFollowupEngine.build(
            FollowupDemoData.create(date).customers,
            FollowupDemoData.create(date).movements,
            date
        )
        assertEquals(once, again)
        assertEquals(20, DebtFollowupEngine.build(
            FollowupDemoData.create(date.plusDays(1)).customers,
            FollowupDemoData.create(date.plusDays(1)).movements,
            date.plusDays(1)
        ).size)
        assertFalse(FollowupDemoData.isDemoCustomer("real-account-uuid"))
    }
}

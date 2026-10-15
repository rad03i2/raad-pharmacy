package com.radwan.raadpharmacy.whatsapp

import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.DebtFollowupEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticWhatsappPlannerTest {
    private val customer = Customer(id = "c1", name = "Test", openingDebt = 1500L, createdAt = 1_700_000_000_000)
    private fun sample(age: Long?, overdue: Long, balance: Long = 1500L,
        special: Long = 0L, unknown: Boolean = false) =
        DebtFollowupEngine.Account(customer, balance, overdue, age, 1_700_000_000_000,
            null, DebtFollowupEngine.Bucket.DAYS_30_TO_59, unknown,
            specialOverdueAmount = special)

    @Test fun onlyGenuineOverdueBalancesQualify() {
        val list = listOf(sample(29, 0), sample(30, 1500), sample(60, 1500),
            sample(7, 1500, special = 500), sample(80, 1500, balance = 0),
            sample(61, 1500, unknown = true))
        assertEquals(listOf(list[1], list[2], list[3]), AutomaticWhatsappPlanner.due(list))
    }
}

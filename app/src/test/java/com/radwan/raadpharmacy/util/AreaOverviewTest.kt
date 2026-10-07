package com.radwan.raadpharmacy.util

import com.radwan.raadpharmacy.data.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AreaOverviewTest {
    @Test fun neighborhoodsNormalizeAndTotalsRespectOpeningDebtAndToday() {
        val today = LocalDate.of(2026,10,7)
        val now = today.atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val yesterday = today.minusDays(1).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val customers = listOf(Customer("a","أحمد",area=" حي  النور ",openingDebt=1000),
            Customer("b","رعد",area="حي النور",openingDebt=500), Customer("c","فؤاد",area=" "))
        val entries = listOf(LedgerEntry("1","a",EntryType.DEBT,1000,createdAt=now),
            LedgerEntry("2","a",EntryType.PAYMENT,750,createdAt=now),
            LedgerEntry("3","b",EntryType.PAYMENT,500,createdAt=yesterday))
        val groups = buildAreaOverview(customers,entries,today)
        val area = groups.single { it.name=="حي النور" }
        assertEquals(2,area.customers.size)
        assertEquals(1250L,area.debt)
        assertEquals(750L,area.collectedToday)
        assertEquals(1,area.openAccounts)
        assertEquals("a",area.customers.first().id)
        assertEquals(0L,groups.single { it.name=="غير محددة" }.debt)
    }
}

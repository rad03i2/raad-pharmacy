package com.radwan.raadpharmacy.data

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class MonthlyReportsTest {
    @Test fun januaryIncludesPriorYearContinuouslySinceFeatureLaunch() {
        assertEquals(listOf("2027-01","2026-12","2026-11","2026-10"),
            MonthlyReports.availableMonths(YearMonth.of(2027,1)).map { it.toString() })
    }
    @Test fun baghdadBoundariesExcludeBothAdjacentMonthsAndCountActualMovements() {
        val period=MonthlyReports.month(YearMonth.of(2027,1))
        assertEquals("2026-12-31T21:00:00Z", java.time.Instant.ofEpochMilli(period.start).toString())
        val entries=listOf(
            LedgerEntry("before","c",EntryType.DEBT,99,createdAt=period.start-1),
            LedgerEntry("first","c",EntryType.DEBT,100,createdAt=period.start),
            LedgerEntry("last","c",EntryType.DEBT,200,createdAt=period.endExclusive-1),
            LedgerEntry("receipt","c",EntryType.PAYMENT,50,createdAt=period.start+1),
            LedgerEntry("next","c",EntryType.PAYMENT,500,createdAt=period.endExclusive))
        assertEquals(MonthlyReports.Totals(300,50,2,1),MonthlyReports.totals(entries,period))
    }
    @Test fun customPeriodCrossesYearAndIncludesFinalDay() {
        val period=MonthlyReports.Period(LocalDate.of(2026,12,30),LocalDate.of(2027,1,2))
        assertEquals(4*86_400_000L,period.endExclusive-period.start)
        assertEquals(29*86_400_000L,MonthlyReports.month(YearMonth.of(2028,2)).endExclusive-MonthlyReports.month(YearMonth.of(2028,2)).start)
    }
    @Test(expected=IllegalArgumentException::class) fun reversedPeriodRejected() {
        MonthlyReports.Period(LocalDate.of(2027,2,2),LocalDate.of(2027,2,1))
    }
}

package com.radwan.raadpharmacy.data

import com.radwan.raadpharmacy.util.ActivityPeriod
import com.radwan.raadpharmacy.util.filterActivity
import com.radwan.raadpharmacy.util.filterActivityOnDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ActivityExactDateTest {
    private val iraq = ZoneId.of("Asia/Baghdad")
    private fun at(day: LocalDate, hour: Int = 0): Long =
        day.atTime(hour, 0).atZone(iraq).toInstant().toEpochMilli()
    private fun entry(id: String, time: Long) =
        LedgerEntry(id, "customer", EntryType.DEBT, 500L, createdAt = time)

    @Test fun selectedDateIncludesItsDayButNotAdjacentDays() {
        val day = LocalDate.of(2026, 10, 12)
        val records = listOf(
            entry("previous", at(day.minusDays(1), 23)),
            entry("midnight", at(day)),
            entry("night", at(day, 23)),
            entry("next", at(day.plusDays(1)))
        )
        assertEquals(
            listOf("night", "midnight"),
            filterActivityOnDate(records, day).map { it.id }
        )
        assertTrue(filterActivityOnDate(records, day.minusDays(4)).isEmpty())
    }

    @Test fun historicalDayCanBeChosenOutsideLastWeek() {
        val day = LocalDate.of(2026, 1, 5)
        val records = listOf(entry("historic", at(day)))
        assertTrue(filterActivity(records, ActivityPeriod.WEEK,
            LocalDate.of(2026, 10, 13), iraq).isEmpty())
        assertEquals(listOf("historic"), filterActivityOnDate(records, day).map { it.id })
    }
}

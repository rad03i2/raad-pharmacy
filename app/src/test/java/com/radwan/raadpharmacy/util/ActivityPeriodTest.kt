package com.radwan.raadpharmacy.util

import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.data.LedgerEntry
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityPeriodTest {
    private val zone = ZoneId.of("Asia/Baghdad")
    private val today = LocalDate.of(2026, 10, 7)
    private fun entry(date: String, hour: Int = 12) = LedgerEntry(
        date + hour, "c1", EntryType.DEBT, 1000,
        createdAt = LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    )

    @Test fun periodsIncludeBothDebtsAndPaymentsAndRespectLocalMidnight() {
        val rows = listOf(entry("2026-10-07", 0), entry("2026-10-06", 23),
            entry("2026-10-01"), entry("2026-09-30"), entry("2026-10-08"),
            entry("2026-10-07", 13).copy(type = EntryType.PAYMENT))
        assertEquals(2, filterActivity(rows, ActivityPeriod.DAY, today, zone).size)
        assertEquals(4, filterActivity(rows, ActivityPeriod.WEEK, today, zone).size)
        assertEquals(4, filterActivity(rows, ActivityPeriod.MONTH, today, zone).size)
        assertEquals(6, filterActivity(rows, ActivityPeriod.ALL, today, zone).size)
    }

    @Test fun timesUseTwelveHoursEnglishDigitsAndArabicDayPeriods() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            assertEquals("12:00 صباحًا", formatTime(entry("2026-10-07", 0).createdAt))
            assertEquals("12:00 مساءً", formatTime(entry("2026-10-07", 12).createdAt))
            assertEquals("1:00 مساءً", formatTime(entry("2026-10-07", 13).createdAt))
        } finally { TimeZone.setDefault(previous) }
    }
}

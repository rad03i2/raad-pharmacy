package com.radwan.raadpharmacy.util

import com.radwan.raadpharmacy.data.LedgerEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ActivityPeriod(val title: String) {
    DAY("اليوم"), WEEK("آخر 7 أيام"), MONTH("هذا الشهر"), ALL("الكل")
}

fun filterActivity(
    entries: List<LedgerEntry>,
    period: ActivityPeriod,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault()
): List<LedgerEntry> {
    val start = when (period) {
        ActivityPeriod.DAY -> today
        ActivityPeriod.WEEK -> today.minusDays(6)
        ActivityPeriod.MONTH -> today.withDayOfMonth(1)
        ActivityPeriod.ALL -> null
    }
    return entries.filter { entry ->
        val day = Instant.ofEpochMilli(entry.createdAt).atZone(zone).toLocalDate()
        start == null || (!day.isBefore(start) && !day.isAfter(today))
    }.sortedWith(compareByDescending<LedgerEntry> { it.createdAt }.thenBy { it.id })
}

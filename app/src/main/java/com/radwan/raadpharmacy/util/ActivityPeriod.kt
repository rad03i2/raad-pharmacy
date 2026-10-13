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

/** Inclusive local calendar day, using a half-open range so midnight boundaries are correct. */
fun filterActivityOnDate(
    entries: List<LedgerEntry>,
    date: LocalDate,
    zone: ZoneId = ZoneId.of("Asia/Baghdad")
): List<LedgerEntry> {
    val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val until = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return entries.filter { it.createdAt >= from && it.createdAt < until }
        .sortedWith(compareByDescending<LedgerEntry> { it.createdAt }.thenBy { it.id })
}

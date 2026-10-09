package com.radwan.raadpharmacy.backup

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields

object BackupRetention {
    fun keep(snapshots: List<Pair<String, Long>>): Set<String> {
        val sorted = snapshots.sortedByDescending { it.second }
        fun date(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
        val daily = sorted.distinctBy { date(it.second) }.take(7)
        val weeks = WeekFields.ISO
        val weekly = sorted.distinctBy { date(it.second).let { day -> day.get(weeks.weekBasedYear()).toString() + ":" + day.get(weeks.weekOfWeekBasedYear()) } }.take(4)
        return (sorted.take(4) + daily + weekly).mapTo(hashSetOf()) { it.first }
    }
}

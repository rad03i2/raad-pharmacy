package com.radwan.raadpharmacy.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Period movement totals, never an all-time balance or a synthetic opening movement. */
object MonthlyReports {
    val zone: ZoneId = ZoneId.of("Asia/Baghdad")
    val firstMonth: YearMonth = YearMonth.of(2026, 10)
    data class Period(val from: LocalDate, val through: LocalDate) {
        init { require(!through.isBefore(from)) { "نهاية الفترة تسبق بدايتها." } }
        val start: Long get() = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val endExclusive: Long get() = through.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    data class Totals(val debts: Long, val payments: Long, val debtCount: Int, val paymentCount: Int)
    fun month(month: YearMonth) = Period(month.atDay(1), month.atEndOfMonth())
    fun availableMonths(now: YearMonth = YearMonth.now(zone)): List<YearMonth> =
        generateSequence(now) { it.minusMonths(1) }.takeWhile { it >= firstMonth }.toList()
    fun totals(entries: List<LedgerEntry>, period: Period): Totals {
        val start = period.start; val end = period.endExclusive
        var debts = 0L; var payments = 0L; var debtCount = 0; var paymentCount = 0
        entries.forEach { entry ->
            if (entry.createdAt >= start && entry.createdAt < end) when (entry.type) {
                EntryType.DEBT -> { debts = Math.addExact(debts, entry.amount); debtCount++ }
                EntryType.PAYMENT -> { payments = Math.addExact(payments, entry.amount); paymentCount++ }
            }
        }
        return Totals(debts, payments, debtCount, paymentCount)
    }
}

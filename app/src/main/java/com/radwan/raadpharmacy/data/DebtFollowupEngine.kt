package com.radwan.raadpharmacy.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Read-only financial ageing projection. Does NOT update Room, cloud movements or balances.
 * FIFO: receipts settle the oldest unpaid tranche, including the opening balance first.
 * Only trustworthy source timestamps qualify a debt for an overdue age.
 */
object DebtFollowupEngine {
    val IRAQ_ZONE: ZoneId = ZoneId.of("Asia/Baghdad")

    enum class Bucket { DAYS_30_TO_59, DAY_60, OVER_60, NEEDS_REVIEW, NOT_OVERDUE }

    data class Account(
        val customer: Customer,
        val balance: Long,
        val overdueAmount: Long,
        val ageDays: Long?,
        val oldestUnpaidAt: Long?,
        val lastPaymentAt: Long?,
        val bucket: Bucket,
        val hasUnknownDates: Boolean
    )

    private data class Tranche(val createdAt: Long, var remaining: Long)

    fun build(
        customers: List<Customer>,
        entries: List<LedgerEntry>,
        today: LocalDate = LocalDate.now(IRAQ_ZONE)
    ): List<Account> {
        val grouped = entries.groupBy { it.customerId }
        return customers.mapNotNull { customer ->
            calculate(customer, grouped[customer.id].orEmpty(), today)
                .takeIf { it.balance > 0 }
        }
    }

    fun calculate(
        customer: Customer,
        entries: List<LedgerEntry>,
        today: LocalDate = LocalDate.now(IRAQ_ZONE)
    ): Account {
        val tranches = ArrayList<Tranche>(entries.size + 1)
        val ordered = entries.filter { it.customerId == customer.id }
            .sortedWith(compareBy<LedgerEntry> { it.createdAt }.thenBy { it.id })

        // The opening debt existed at account creation. It is not an ordinary
        // movement; do not substitute this date when the original age is unknown.
        if (customer.openingDebt > 0) tranches += Tranche(customer.createdAt, customer.openingDebt)
        var firstActive = 0
        var credit = 0L
        var lastPaymentAt: Long? = null

        for (entry in ordered) {
            if (entry.amount <= 0L) continue
            when (entry.type) {
                EntryType.DEBT -> {
                    val consumed = minOf(credit, entry.amount)
                    credit -= consumed
                    val owing = entry.amount - consumed
                    if (owing > 0L) tranches += Tranche(entry.createdAt, owing)
                }
                EntryType.PAYMENT -> {
                    lastPaymentAt = maxOf(lastPaymentAt ?: entry.createdAt, entry.createdAt)
                    var left = entry.amount
                    while (left > 0 && firstActive < tranches.size) {
                        val tranche = tranches[firstActive]
                        if (tranche.remaining <= 0) {
                            firstActive++
                            continue
                        }
                        val applied = minOf(left, tranche.remaining)
                        tranche.remaining -= applied
                        left -= applied
                        if (tranche.remaining == 0L) firstActive++
                    }
                    // In malformed or out-of-order legacy data a credit can
                    // precede a debt. Apply it to a later debt rather than
                    // inventing negative outstanding amounts.
                    if (left > 0) credit = Math.addExact(credit, left)
                }
            }
        }

        val unpaid = tranches.filter { it.remaining > 0L }
        val balance = unpaid.sumOf { it.remaining }
        if (balance == 0L) {
            return Account(customer, 0L, 0L, null, null, lastPaymentAt,
                Bucket.NOT_OVERDUE, hasUnknownDates = false)
        }

        fun ageOrNull(timestamp: Long): Long? {
            if (timestamp <= 0L) return null
            val date = runCatching { Instant.ofEpochMilli(timestamp).atZone(IRAQ_ZONE).toLocalDate() }
                .getOrNull() ?: return null
            if (date.isAfter(today)) return null
            return ChronoUnit.DAYS.between(date, today)
        }

        val ages = unpaid.map { it to ageOrNull(it.createdAt) }
        val hasUnknownDates = ages.any { it.second == null }
        val oldest = unpaid.minByOrNull { it.createdAt }?.createdAt
        val oldestAge = if (hasUnknownDates) null else ages.maxOfOrNull { it.second ?: 0L }
        val overdue = ages.filter { (lot, age) -> lot.remaining > 0L && age != null && age >= 30L }
            .sumOf { it.first.remaining }

        val bucket = when {
            hasUnknownDates -> Bucket.NEEDS_REVIEW
            oldestAge == null || oldestAge < 30 -> Bucket.NOT_OVERDUE
            oldestAge == 60L -> Bucket.DAY_60
            oldestAge > 60L -> Bucket.OVER_60
            else -> Bucket.DAYS_30_TO_59
        }
        return Account(customer, balance, overdue, oldestAge,
            if (hasUnknownDates) null else oldest, lastPaymentAt, bucket, hasUnknownDates)
    }
}

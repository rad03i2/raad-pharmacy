package com.radwan.raadpharmacy.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DebtFollowupEngineTest {
    private val today = LocalDate.of(2026, 10, 9)
    private val zone = DebtFollowupEngine.IRAQ_ZONE

    private fun ago(days: Long) =
        today.minusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun customer(opening: Long = 0L, created: Long = ago(100)) =
        Customer(id = "customer", name = "زبون", openingDebt = opening, createdAt = created)

    private fun debt(id: String, amount: Long, days: Long) =
        LedgerEntry(id = id, customerId = "customer", type = EntryType.DEBT,
            amount = amount, createdAt = ago(days))

    private fun payment(id: String, amount: Long, days: Long) =
        LedgerEntry(id = id, customerId = "customer", type = EntryType.PAYMENT,
            amount = amount, createdAt = ago(days))

    private fun calculate(c: Customer = customer(), vararg moves: LedgerEntry) =
        DebtFollowupEngine.calculate(c, moves.toList(), today)

    @Test fun boundary29IsNotOverdue() {
        val result = calculate(moves = *arrayOf(debt("a", 50_000, 29)))
        assertEquals(DebtFollowupEngine.Bucket.NOT_OVERDUE, result.bucket)
        assertEquals(0, result.overdueAmount)
    }

    @Test fun boundary30IsLate() {
        val result = calculate(moves = *arrayOf(debt("a", 50_000, 30)))
        assertEquals(DebtFollowupEngine.Bucket.DAYS_30_TO_59, result.bucket)
        assertEquals(30, result.ageDays)
        assertEquals(50_000L, result.overdueAmount)
    }

    @Test fun boundary59IsLateAnd60IsDistinct() {
        assertEquals(DebtFollowupEngine.Bucket.DAYS_30_TO_59,
            calculate(moves = *arrayOf(debt("a", 100, 59))).bucket)
        assertEquals(DebtFollowupEngine.Bucket.DAY_60,
            calculate(moves = *arrayOf(debt("a", 100, 60))).bucket)
    }

    @Test fun boundary61And150RemainOldDebt() {
        assertEquals(DebtFollowupEngine.Bucket.OVER_60,
            calculate(moves = *arrayOf(debt("a", 100, 61))).bucket)
        assertEquals(150, calculate(moves = *arrayOf(debt("a", 100, 150))).ageDays)
    }

    @Test fun partialPaymentNeverResetsAge() {
        val result = calculate(moves = *arrayOf(debt("a", 50_000, 30), payment("p", 10_000, 10)))
        assertEquals(40_000L, result.balance)
        assertEquals(40_000L, result.overdueAmount)
        assertEquals(30, result.ageDays)
    }

    @Test fun fullyPaidCustomerDisappearsFromAgeing() {
        val c = customer()
        val rows = DebtFollowupEngine.build(
            listOf(c), listOf(debt("a", 50_000, 50), payment("p", 50_000, 1)), today)
        assertTrue(rows.isEmpty())
    }

    @Test fun twoDebtsAndPaymentRemoveOldestFirst() {
        val result = calculate(moves = *arrayOf(
            debt("old", 20_000, 80), debt("new", 40_000, 5), payment("p", 20_000, 1)))
        assertEquals(40_000L, result.balance)
        assertEquals(0L, result.overdueAmount)
        assertEquals(5, result.ageDays)
        assertEquals(DebtFollowupEngine.Bucket.NOT_OVERDUE, result.bucket)
    }

    @Test fun twoDebtsWithPartialFifoOnlyOldPartIsOverdue() {
        val result = calculate(moves = *arrayOf(
            debt("old", 50_000, 80), debt("new", 40_000, 5), payment("p", 20_000, 1)))
        assertEquals(70_000L, result.balance)
        assertEquals(30_000L, result.overdueAmount)
        assertEquals(80, result.ageDays)
    }

    @Test fun customerAgeIsNotUsedWhenThereIsNoOpeningDebt() {
        val result = calculate(customer(created = ago(200)), debt("recent", 500, 5))
        assertEquals(5L, result.ageDays)
        assertEquals(DebtFollowupEngine.Bucket.NOT_OVERDUE, result.bucket)
    }

    @Test fun openingDebtUsesAccountCreationWhenKnown() {
        val result = calculate(customer(opening = 1000, created = ago(90)),
            payment("p", 250, 25))
        assertEquals(750L, result.balance)
        assertEquals(750L, result.overdueAmount)
        assertEquals(90L, result.ageDays)
    }

    @Test fun untrustedOpeningDateGoesToManualReview() {
        val result = calculate(customer(opening = 800, created = 0),
            debt("new", 200, 5))
        assertEquals(DebtFollowupEngine.Bucket.NEEDS_REVIEW, result.bucket)
        assertNull(result.ageDays)
        assertEquals(1_000L, result.balance)
    }

    @Test fun futureDebtIsNotAssignedInventedAge() {
        val result = calculate(moves = *arrayOf(
            LedgerEntry(id = "future", customerId = "customer", type = EntryType.DEBT,
                amount = 400, createdAt = today.plusDays(2)
                    .atStartOfDay(zone).toInstant().toEpochMilli())))
        assertEquals(DebtFollowupEngine.Bucket.NEEDS_REVIEW, result.bucket)
    }

    @Test fun outOfOrderInputIsChronologicallySorted() {
        val result = calculate(moves = *arrayOf(
            payment("p", 9_000, 2), debt("recent", 10_000, 5), debt("old", 8_000, 50)))
        assertEquals(9_000L, result.balance)
        assertEquals(0L, result.overdueAmount)
        assertEquals(5, result.ageDays)
    }

    @Test fun allCustomersAreUniqueAndUnknownNotInOverdueStats() {
        val unknown = customer(opening = 100, created = 0)
        val other = Customer(id = "other", name = "ثان", openingDebt = 0, createdAt = ago(90))
        val rows = DebtFollowupEngine.build(
            listOf(unknown, other),
            listOf(LedgerEntry("entry", "other", EntryType.DEBT, 500, createdAt = ago(60))), today)
        assertEquals(2, rows.size)
        assertEquals(2, rows.distinctBy { it.customer.id }.size)
        assertEquals(1, rows.count { it.bucket == DebtFollowupEngine.Bucket.DAY_60 })
        assertEquals(1, rows.count { it.bucket == DebtFollowupEngine.Bucket.NEEDS_REVIEW })
    }

    @Test fun invalidCreditMovementDoesNotCreateNegativeDebt() {
        val result = calculate(moves = *arrayOf(payment("p", 1000, 20), debt("later", 500, 10)))
        assertEquals(0L, result.balance)
    }
}

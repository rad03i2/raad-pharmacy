package com.radwan.raadpharmacy.data

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepaymentTermsTest {
    private val zone = ZoneId.of("Asia/Baghdad")
    private val start = LocalDate.of(2026, 10, 1)
    private fun at(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val customer = Customer("c1", "زبون", createdAt = at(start))

    @Test fun codecRoundTripPreservesHumanDetails() {
        val encoded = RepaymentTerms.pack("دواء الضغط", 7)
        assertEquals("دواء الضغط" to 7, RepaymentTerms.unpack(encoded))
        assertEquals("دواء الضغط", RepaymentTerms.pack(encoded, null))
        assertNull(RepaymentTerms.unpack("دون تذكير").second)
    }

    @Test fun reminderIsLateOnlyAfterDueDate() {
        val entries = listOf(LedgerEntry("e1", customer.id, EntryType.DEBT, 50_000,
            createdAt = at(start), repaymentDays = 2))
        val dueDay = DebtFollowupEngine.calculate(customer, entries, start.plusDays(2))
        assertEquals(0L, dueDay.specialOverdueAmount)
        val late = DebtFollowupEngine.calculate(customer, entries, start.plusDays(3))
        assertEquals(50_000L, late.specialOverdueAmount)
        assertEquals(2, late.specialReminderDays)
        assertEquals(50_000L, late.overdueAmount)
        assertEquals(DebtFollowupEngine.Bucket.NOT_OVERDUE, late.bucket)
    }

    @Test fun fifoPaymentReducesTheSpecificReminderAmount() {
        val entries = listOf(
            LedgerEntry("a", customer.id, EntryType.DEBT, 10_000, createdAt = at(start)),
            LedgerEntry("b", customer.id, EntryType.DEBT, 50_000,
                createdAt = at(start.plusDays(1)), repaymentDays = 1),
            LedgerEntry("p", customer.id, EntryType.PAYMENT, 35_000,
                createdAt = at(start.plusDays(2)))
        )
        val late = DebtFollowupEngine.calculate(customer, entries, start.plusDays(4))
        assertEquals(25_000L, late.balance)
        assertEquals(25_000L, late.specialOverdueAmount)
        assertEquals(25_000L, late.overdueAmount)
        val settled = DebtFollowupEngine.calculate(customer,
            entries + LedgerEntry("p2", customer.id, EntryType.PAYMENT, 25_000,
                createdAt = at(start.plusDays(4))), start.plusDays(5))
        assertEquals(0L, settled.balance)
        assertEquals(0L, settled.specialOverdueAmount)
    }

    @Test fun defaultDebtsStayOnNormalThirtyDayRule() {
        val entries = listOf(LedgerEntry("e", customer.id, EntryType.DEBT, 50_000, createdAt = at(start)))
        val before = DebtFollowupEngine.calculate(customer, entries, start.plusDays(5))
        assertEquals(0L, before.specialOverdueAmount)
        assertEquals(DebtFollowupEngine.Bucket.NOT_OVERDUE, before.bucket)
        val after = DebtFollowupEngine.calculate(customer, entries, start.plusDays(30))
        assertEquals(50_000L, after.overdueAmount)
        assertEquals(DebtFollowupEngine.Bucket.DAYS_30_TO_59, after.bucket)
    }
}

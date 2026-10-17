package com.radwan.raadpharmacy.data

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ShortRepaymentTest {
    private val at=Instant.parse("2026-10-17T12:00:00Z").toEpochMilli()
    private val customer=Customer("c","fixture",createdAt=at)
    @Test fun eachShortTermBecomesDueAtItsExactMinuteAndPaymentsSettleTheActualDebt() {
        RepaymentTerms.shortChoices.forEach { term ->
            val debt=LedgerEntry("d","c",EntryType.DEBT,100,createdAt=at,repaymentDays=term)
            val due=RepaymentTerms.shortDueAt(debt)!!
            assertEquals(at-term*60_000L,due)
            assertEquals(0L,DebtFollowupEngine.calculate(customer,listOf(debt),LocalDate.of(2026,10,17),due-1).specialOverdueAmount)
            assertEquals(100L,DebtFollowupEngine.calculate(customer,listOf(debt),LocalDate.of(2026,10,17),due).specialOverdueAmount)
            val paid=DebtFollowupEngine.calculate(customer,listOf(debt,LedgerEntry("p","c",EntryType.PAYMENT,100,createdAt=at+1)),
                LocalDate.of(2026,10,17),due)
            assertFalse("Paid short debt must not notify",debt.id in paid.unpaidEntryIds)
            assertEquals(0L,paid.specialOverdueAmount)
        }
    }
    @Test fun partialPaymentsRemainAttachedToUnpaidTranches() {
        val first=LedgerEntry("a","c",EntryType.DEBT,100,createdAt=at,repaymentDays=-5)
        val second=first.copy(id="b",createdAt=at+1)
        val payment=LedgerEntry("p","c",EntryType.PAYMENT,150,createdAt=at+2)
        val account=DebtFollowupEngine.calculate(customer,listOf(first,second,payment),LocalDate.of(2026,10,17),at+600_000)
        assertEquals(setOf("b"),account.unpaidEntryIds);assertEquals(50L,account.specialOverdueAmount)
    }
    @Test fun allOldAndNewAgreementsRoundTripAndShortChoicesAreOptIn() {
        RepaymentTerms.choices.forEach { term -> assertEquals("دواء" to term,RepaymentTerms.unpack(RepaymentTerms.pack("دواء",term))) }
        assertFalse(RepaymentTerms.visibleChoices(false).any { it in RepaymentTerms.shortChoices })
        assertTrue(RepaymentTerms.visibleChoices(false).containsAll(listOf(1,2,7,14,21,28,60)))
        assertEquals(RepaymentTerms.shortChoices,RepaymentTerms.visibleChoices(true).takeLast(4))
    }
    @Test fun extendedDayTermsKeepOriginalNextDayOverdueSemantics() {
        listOf(21,28,60).forEach { term ->
            val debt=LedgerEntry("d","c",EntryType.DEBT,100,createdAt=at,repaymentDays=term)
            val date=Instant.ofEpochMilli(at).atZone(DebtFollowupEngine.IRAQ_ZONE).toLocalDate()
            assertEquals(0L,DebtFollowupEngine.calculate(customer,listOf(debt),date.plusDays(term.toLong())).specialOverdueAmount)
            assertEquals(100L,DebtFollowupEngine.calculate(customer,listOf(debt),date.plusDays(term+1L)).specialOverdueAmount)
        }
    }
}

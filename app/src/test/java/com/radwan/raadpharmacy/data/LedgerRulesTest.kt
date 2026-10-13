package com.radwan.raadpharmacy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerRulesTest {
    private val customer = Customer(
        id = "c1",
        name = "أحمد",
        openingDebt = 10_000L,
        createdAt = 1_000L
    )

    @Test
    fun openingDebt_debtAndPartialPayment_produceCorrectBalance() {
        val entries = listOf(
            LedgerEntry(
                id = "e1",
                customerId = customer.id,
                type = EntryType.DEBT,
                amount = 5_000L,
                createdAt = 2_000L
            ),
            LedgerEntry(
                id = "e2",
                customerId = customer.id,
                type = EntryType.PAYMENT,
                amount = 3_000L,
                createdAt = 3_000L
            )
        )

        assertEquals(12_000L, customerBalance(customer, entries))
        assertTrue(LedgerRules.isChronologicallyValid(customer, entries))
    }

    @Test
    fun fullPayment_closesAccountExactlyAtZero() {
        val entries = listOf(
            LedgerEntry(
                id = "e1",
                customerId = customer.id,
                type = EntryType.PAYMENT,
                amount = 10_000L,
                createdAt = 2_000L
            )
        )

        assertEquals(0L, customerBalance(customer, entries))
        assertTrue(LedgerRules.isChronologicallyValid(customer, entries))
    }

    @Test
    fun paymentGreaterThanAvailableBalance_isRejected() {
        val entries = listOf(
            LedgerEntry(
                id = "e1",
                customerId = customer.id,
                type = EntryType.PAYMENT,
                amount = 10_001L,
                createdAt = 2_000L
            )
        )

        assertFalse(LedgerRules.isChronologicallyValid(customer, entries))
    }

    @Test
    fun reducingOldDebtBelowLaterPayment_isRejected() {
        val noOpeningDebt = customer.copy(openingDebt = 0L)
        val editedHistory = listOf(
            LedgerEntry(
                id = "debt",
                customerId = customer.id,
                type = EntryType.DEBT,
                amount = 3_000L,
                createdAt = 2_000L
            ),
            LedgerEntry(
                id = "payment",
                customerId = customer.id,
                type = EntryType.PAYMENT,
                amount = 5_000L,
                createdAt = 3_000L
            )
        )

        assertFalse(LedgerRules.isChronologicallyValid(noOpeningDebt, editedHistory))
    }

    @Test
    fun deletingOldDebtThatSupportsLaterPayment_isRejected() {
        val noOpeningDebt = customer.copy(openingDebt = 0L)
        val remainingHistory = listOf(
            LedgerEntry(
                id = "payment",
                customerId = customer.id,
                type = EntryType.PAYMENT,
                amount = 5_000L,
                createdAt = 3_000L
            )
        )

        assertFalse(LedgerRules.isChronologicallyValid(noOpeningDebt, remainingHistory))
    }

    @Test
    fun sameTimestamp_usesEntryIdAsStableTieBreaker() {
        val noOpeningDebt = customer.copy(openingDebt = 0L)
        val validOrder = listOf(
            LedgerEntry(
                id = "a-debt",
                customerId = customer.id,
                type = EntryType.DEBT,
                amount = 5_000L,
                createdAt = 2_000L
            ),
            LedgerEntry(
                id = "b-payment",
                customerId = customer.id,
                type = EntryType.PAYMENT,
                amount = 5_000L,
                createdAt = 2_000L
            )
        )
        val invalidOrder = listOf(
            LedgerEntry(
                id = "a-payment",
                customerId = customer.id,
                type = EntryType.PAYMENT,
                amount = 5_000L,
                createdAt = 2_000L
            ),
            LedgerEntry(
                id = "b-debt",
                customerId = customer.id,
                type = EntryType.DEBT,
                amount = 5_000L,
                createdAt = 2_000L
            )
        )

        assertTrue(LedgerRules.isChronologicallyValid(noOpeningDebt, validOrder))
        assertFalse(LedgerRules.isChronologicallyValid(noOpeningDebt, invalidOrder))
    }
    @Test
    fun deletingDebtNeverCreatesCompensatingDebitAndLaterEditsRemainPossible() {
        val c = customer.copy(openingDebt = 0L)
        val removed = LedgerEntry("debt", c.id, EntryType.DEBT, 5000, createdAt = 2000)
        val paid = LedgerEntry("paid", c.id, EntryType.PAYMENT, 2000, createdAt = 3000)
        val remaining = listOf(paid)
        assertEquals(0L, customerBalance(c, remaining))
        assertFalse(LedgerRules.isChronologicallyValid(c, remaining))
        val laterDebt = LedgerEntry("new", c.id, EntryType.DEBT, 6000, createdAt = 4000)
        val revised = laterDebt.copy(amount = 4500)
        assertTrue(LedgerRules.isEditChronologicallySafe(c,
            remaining + laterDebt, remaining + revised))
        assertFalse(LedgerRules.isEditChronologicallySafe(c,
            listOf(removed, paid), listOf(removed.copy(amount = 1000), paid)))
        assertEquals(2500L, customerBalance(c, remaining + revised))
    }

}

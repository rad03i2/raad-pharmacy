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
}

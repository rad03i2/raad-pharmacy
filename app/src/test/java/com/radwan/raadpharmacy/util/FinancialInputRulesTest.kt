package com.radwan.raadpharmacy.util

import org.junit.Assert.*
import org.junit.Test

class FinancialInputRulesTest {
    @Test fun repeatedAndMixedTapsAccumulate() {
        var amount = accumulatedAmount("", 500L)!!
        amount = accumulatedAmount(amount, 500L)!!
        assertEquals("1000", amount)
        assertEquals("2000", accumulatedAmount(amount, 1000L))
        assertEquals("2250", accumulatedAmount("2000", 250L))
    }
    @Test fun amountLimitCannotOverflow() {
        assertEquals("999999999999", accumulatedAmount("999999999749", 250L))
        assertNull(accumulatedAmount("999999999999", 250L))
        assertNull(accumulatedAmount("500", Long.MAX_VALUE))
        assertNull(accumulatedAmount("-500", 250L))
    }
    @Test fun logoutRequiresExactFixedAnswer() {
        assertTrue(SignOutChallenge.accepts(((2012L * 10000L) + (45L * 45L) + 1L).toString()))
        listOf("", "2012-2026", "075932", "2012202", "20122027").forEach { assertFalse(SignOutChallenge.accepts(it)) }
    }
}

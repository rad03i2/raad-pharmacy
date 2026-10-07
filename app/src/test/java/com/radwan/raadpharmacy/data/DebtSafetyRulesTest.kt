package com.radwan.raadpharmacy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class DebtSafetyRulesTest {

    @Test
    fun veryLargeAmountAgainstStableHistory_warns() {
        val warning = DebtSafetyRules.anomalyWarning(
            recentDebtAmounts = listOf(4_000L, 5_000L, 6_000L, 5_000L, 5_500L),
            candidateAmount = 500_000L
        )

        assertNotNull(warning)
        assertEquals(5_000L, warning?.typicalAmount)
        assertEquals(500_000L, warning?.enteredAmount)
    }

    @Test
    fun normalAmountNearCustomerPattern_doesNotWarn() {
        val warning = DebtSafetyRules.anomalyWarning(
            recentDebtAmounts = listOf(4_000L, 5_000L, 6_000L, 5_000L, 5_500L),
            candidateAmount = 6_000L
        )

        assertNull(warning)
    }

    @Test
    fun oneOldOutlier_doesNotDistortTypicalAmount() {
        val warning = DebtSafetyRules.anomalyWarning(
            recentDebtAmounts = listOf(
                5_000L,
                6_000L,
                4_000L,
                5_500L,
                500_000L,
                5_000L
            ),
            candidateAmount = 250_000L
        )

        assertNotNull(warning)
        assertEquals(5_250L, warning?.typicalAmount)
    }

    @Test
    fun insufficientHistory_doesNotWarn() {
        val warning = DebtSafetyRules.anomalyWarning(
            recentDebtAmounts = listOf(5_000L, 6_000L, 4_000L),
            candidateAmount = 500_000L
        )

        assertNull(warning)
    }
}

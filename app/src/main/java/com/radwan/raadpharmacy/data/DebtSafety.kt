package com.radwan.raadpharmacy.data

data class DebtAnomalyWarning(
    val enteredAmount: Long,
    val typicalAmount: Long,
    val comparisonCount: Int
)

sealed interface DebtCreateResult {
    data class Created(val entry: LedgerEntry) : DebtCreateResult

    data class DuplicateDetected(
        val previousEntry: LedgerEntry,
        val secondsAgo: Long
    ) : DebtCreateResult
}

object DebtSafetyRules {
    const val DUPLICATE_WINDOW_MILLIS: Long = 15_000L
    const val ANOMALY_HISTORY_LIMIT: Int = 9
    const val MIN_HISTORY_FOR_ANOMALY: Int = 4

    fun anomalyWarning(
        recentDebtAmounts: List<Long>,
        candidateAmount: Long
    ): DebtAnomalyWarning? {
        if (candidateAmount <= 0L) return null

        val cleanHistory = recentDebtAmounts
            .asSequence()
            .filter { it > 0L }
            .take(ANOMALY_HISTORY_LIMIT)
            .sorted()
            .toList()

        if (cleanHistory.size < MIN_HISTORY_FOR_ANOMALY) return null

        val median = median(cleanHistory)
        if (median <= 0L) return null

        val deviations = cleanHistory
            .map { kotlin.math.abs(it - median) }
            .sorted()
        val mad = median(deviations)

        val ratioThreshold = safeMultiply(median, 5L)
        val spreadThreshold = median + maxOf(20_000L, safeMultiply(mad, 8L))
        val warningThreshold = maxOf(ratioThreshold, spreadThreshold)

        if (candidateAmount < warningThreshold) return null

        return DebtAnomalyWarning(
            enteredAmount = candidateAmount,
            typicalAmount = median,
            comparisonCount = cleanHistory.size
        )
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val middle = values.size / 2
        return if (values.size % 2 == 1) {
            values[middle]
        } else {
            val a = values[middle - 1]
            val b = values[middle]
            a + (b - a) / 2L
        }
    }

    private fun safeMultiply(value: Long, factor: Long): Long =
        if (value > Long.MAX_VALUE / factor) Long.MAX_VALUE else value * factor
}

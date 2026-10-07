package com.radwan.raadpharmacy.util

val financialQuickAmounts = listOf(250L, 500L, 750L, 1_000L, 5_000L, 10_000L, 15_000L, 25_000L, 50_000L)

fun accumulatedAmount(current: String, addition: Long): String? {
    val before = current.toLongOrNull() ?: 0L
    val limit = 999_999_999_999L
    if (before < 0L || addition < 0L || addition > limit || before > limit - addition) return null
    return (before + addition).toString()
}

object SignOutChallenge {
    const val expression = "(2012 × 10000) + (45 × 45) + 1"
    const val answer = "20122026"
    fun accepts(input: String): Boolean = input.trim() == answer
}

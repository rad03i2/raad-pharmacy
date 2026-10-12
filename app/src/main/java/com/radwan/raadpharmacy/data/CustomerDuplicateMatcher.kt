package com.radwan.raadpharmacy.data

import java.util.Locale
import kotlin.math.max

data class CustomerDuplicateMatches(
    val sameName: List<Customer>,
    val samePhone: List<Customer>
) {
    val hasMatches: Boolean get() = sameName.isNotEmpty() || samePhone.isNotEmpty()
}

/** Conservative duplicate detection for new customer records; matches are warnings, never blocks. */
fun findCustomerDuplicates(
    name: String,
    phone: String,
    customers: List<Customer>
): CustomerDuplicateMatches {
    val normalizedName = normalizeCustomerName(name)
    val normalizedPhone = normalizeCustomerPhoneForDuplicate(phone)

    val sameName = if (normalizedName.isBlank()) emptyList() else customers.filter { existing ->
        val candidate = normalizeCustomerName(existing.name)
        candidate.isNotBlank() && namesLikelyMatch(normalizedName, candidate)
    }
    val samePhone = if (normalizedPhone.isBlank()) emptyList() else customers.filter { existing ->
        normalizeCustomerPhoneForDuplicate(existing.phone.orEmpty()) == normalizedPhone
    }
    return CustomerDuplicateMatches(
        sameName = sameName.distinctBy { it.id },
        samePhone = samePhone.distinctBy { it.id }
    )
}

internal fun normalizeCustomerName(value: String): String {
    val normalized = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
        .replace(Regex("[أإآٱ]"), "ا")
        .replace('ى', 'ي')
        .replace('ة', 'ه')
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return normalized
}

internal fun normalizeCustomerPhoneForDuplicate(value: String): String {
    val digits = value.filter(Char::isDigit)
    return when {
        digits.startsWith("00964") -> digits.removePrefix("00964").removePrefix("0")
        digits.startsWith("964") -> digits.removePrefix("964").removePrefix("0")
        digits.startsWith("0") -> digits.removePrefix("0")
        else -> digits
    }
}

private fun namesLikelyMatch(first: String, second: String): Boolean {
    if (first == second) return true
    if (first.split(' ').filter(String::isNotBlank).sorted() ==
        second.split(' ').filter(String::isNotBlank).sorted()
    ) return true

    val compactFirst = first.replace(" ", "")
    val compactSecond = second.replace(" ", "")
    val longest = max(compactFirst.length, compactSecond.length)
    if (longest < 5) return false
    val distance = levenshtein(compactFirst, compactSecond)
    return 1.0 - distance.toDouble() / longest.toDouble() >= 0.88
}

private fun levenshtein(first: String, second: String): Int {
    if (first == second) return 0
    if (first.isEmpty()) return second.length
    if (second.isEmpty()) return first.length
    var previous = IntArray(second.length + 1) { it }
    var current = IntArray(second.length + 1)
    for (i in first.indices) {
        current[0] = i + 1
        for (j in second.indices) {
            val substitution = previous[j] + if (first[i] == second[j]) 0 else 1
            current[j + 1] = minOf(
                previous[j + 1] + 1,
                current[j] + 1,
                substitution
            )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[second.length]
}

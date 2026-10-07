package com.radwan.raadpharmacy.util

import com.radwan.raadpharmacy.data.Customer

object CustomerSearch {

    fun rank(
        customers: List<Customer>,
        query: String,
        balance: (Customer) -> Long,
        recentAt: (Customer) -> Long
    ): List<Customer> {
        val normalizedQuery = normalizeText(query)
        if (normalizedQuery.isBlank()) {
            return customers.sortedByDescending(recentAt)
        }

        val queryTokens = normalizedQuery.split(' ').filter { it.isNotBlank() }
        val queryDigits = normalizeDigits(query)

        return customers
            .mapNotNull { customer ->
                val score = score(customer, normalizedQuery, queryTokens, queryDigits)
                if (score <= 0) null else Ranked(customer, score)
            }
            .sortedWith(
                compareByDescending<Ranked> { it.score }
                    .thenByDescending { balance(it.customer) }
                    .thenByDescending { recentAt(it.customer) }
                    .thenBy { normalizeText(it.customer.name) }
            )
            .map { it.customer }
    }

    internal fun normalizeText(value: String): String {
        if (value.isBlank()) return ""
        return buildString(value.length) {
            value.forEach { ch ->
                when (ch) {
                    'ـ',
                    in 'ً'..'ٟ',
                    'ٰ' -> Unit

                    'أ', 'إ', 'آ', 'ٱ' -> append('ا')
                    'ؤ' -> append('و')
                    'ئ', 'ى' -> append('ي')
                    'ة' -> append('ه')
                    '٠', '۰' -> append('0')
                    '١', '۱' -> append('1')
                    '٢', '۲' -> append('2')
                    '٣', '۳' -> append('3')
                    '٤', '۴' -> append('4')
                    '٥', '۵' -> append('5')
                    '٦', '۶' -> append('6')
                    '٧', '۷' -> append('7')
                    '٨', '۸' -> append('8')
                    '٩', '۹' -> append('9')
                    else -> append(ch.lowercaseChar())
                }
            }
        }
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun score(
        customer: Customer,
        query: String,
        tokens: List<String>,
        digits: String
    ): Int {
        val name = normalizeText(customer.name)
        val area = normalizeText(customer.area)
        val address = normalizeText(customer.address)
        val notes = normalizeText(customer.notes)
        val phone = normalizeDigits(customer.phone.orEmpty())

        var score = 0

        score = maxOf(score, when {
            name == query -> 1200
            name.startsWith(query) -> 1000
            wordStartsWith(name, query) -> 920
            name.contains(query) -> 820
            else -> 0
        })

        if (tokens.size > 1 && tokens.all { token -> name.contains(token) }) {
            score = maxOf(score, 900)
        }

        if (digits.isNotBlank()) {
            score = maxOf(score, when {
                phone == digits -> 1150
                phone.startsWith(digits) -> 950
                phone.contains(digits) -> 760
                else -> 0
            })
        }

        score = maxOf(score, when {
            area == query -> 720
            area.startsWith(query) -> 640
            area.contains(query) -> 560
            else -> 0
        })

        score = maxOf(score, when {
            address.startsWith(query) -> 500
            address.contains(query) -> 440
            notes.contains(query) -> 300
            else -> 0
        })

        val searchable = listOf(name, area, address, notes)
        if (tokens.isNotEmpty() && tokens.all { token ->
                searchable.any { field -> field.contains(token) } ||
                    (digits.isNotBlank() && phone.contains(digits))
            }
        ) {
            score = maxOf(score, 520)
        }

        return score
    }

    private fun wordStartsWith(text: String, query: String): Boolean =
        text.split(' ').any { it.startsWith(query) }

    private fun normalizeDigits(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '٠', '۰' -> append('0')
                '١', '۱' -> append('1')
                '٢', '۲' -> append('2')
                '٣', '۳' -> append('3')
                '٤', '۴' -> append('4')
                '٥', '۵' -> append('5')
                '٦', '۶' -> append('6')
                '٧', '۷' -> append('7')
                '٨', '۸' -> append('8')
                '٩', '۹' -> append('9')
                in '0'..'9' -> append(ch)
                else -> Unit
            }
        }
    }

    private data class Ranked(
        val customer: Customer,
        val score: Int
    )
}

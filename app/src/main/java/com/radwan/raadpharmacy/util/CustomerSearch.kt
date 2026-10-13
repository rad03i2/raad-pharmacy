package com.radwan.raadpharmacy.util

import com.radwan.raadpharmacy.data.Customer

/** Precompute normalized customer fields once per customer snapshot, not at every keystroke. */
object CustomerSearch {
    data class Indexed internal constructor(
        val customer: Customer,
        val name: String,
        val area: String,
        val address: String,
        val notes: String,
        val phone: String,
        val internationalPhone: String
    )

    fun buildIndex(customers: List<Customer>): List<Indexed> =
        customers.map { customer ->
            val phone = normalizeDigits(customer.phone.orEmpty())
            val local = localIraqPhone(phone)
            Indexed(
                customer = customer,
                name = normalizeText(customer.name),
                area = normalizeText(customer.area),
                address = normalizeText(customer.address),
                notes = normalizeText(customer.notes),
                phone = local,
                internationalPhone = if (local.startsWith("07") && local.length == 11)
                    "964" + local.drop(1) else phone
            )
        }

    fun rank(
        customers: List<Customer>, query: String,
        balance: (Customer) -> Long, recentAt: (Customer) -> Long
    ): List<Customer> = rankIndexed(buildIndex(customers), query, balance, recentAt)

    fun rankIndexed(
        index: List<Indexed>, query: String,
        balance: (Customer) -> Long, recentAt: (Customer) -> Long
    ): List<Customer> {
        val normalized = normalizeText(query)
        val digits = normalizeDigits(query)
        val digitsOnly = digits.isNotBlank() &&
            query.all { ch -> ch.isWhitespace() || ch in "()+-." || ch.isDigit() }
        val canonicalPhone = localIraqPhone(digits)
        if (normalized.isEmpty() && digits.isEmpty()) {
            return index.asSequence().sortedByDescending { recentAt(it.customer) }
                .map { it.customer }.toList()
        }
        val words = normalized.split(' ').filter(String::isNotBlank)
        return index.asSequence()
            .mapNotNull { item ->
                val score = score(item, normalized, words, digits, canonicalPhone, digitsOnly)
                if (score <= 0) null else Scored(item, score)
            }
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenByDescending { balance(it.data.customer) }
                    .thenByDescending { recentAt(it.data.customer) }
                    .thenBy { it.data.name }
                    .thenBy { it.data.customer.id }
            ).map { it.data.customer }.toList()
    }

    internal fun normalizeText(value: String): String {
        if (value.isBlank()) return ""
        return buildString(value.length) {
            value.forEach { ch ->
                when (ch) {
                    'ـ', in 'ً'..'ٟ', 'ٰ' -> Unit
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
        }.trim().replace(Regex("\\s+"), " ")
    }

    private fun score(
        item: Indexed, query: String, words: List<String>,
        digits: String, canonicalPhone: String, phoneOnly: Boolean
    ): Int {
        val phoneScore = when {
            digits.isBlank() -> 0
            item.phone == canonicalPhone || item.internationalPhone == digits -> 1300
            item.phone.startsWith(canonicalPhone) || item.internationalPhone.startsWith(digits) -> 1100
            item.phone.contains(canonicalPhone) || item.internationalPhone.contains(digits) -> 900
            else -> 0
        }
        if (phoneOnly) return phoneScore

        val nameScore = when {
            item.name == query -> 1250
            item.name.startsWith(query) -> 1050
            item.name.split(' ').any { it.startsWith(query) } -> 960
            item.name.contains(query) -> 860
            words.size > 1 && words.all { it in item.name } -> 825
            else -> 0
        }
        val otherScore = when {
            item.area == query -> 600
            item.area.startsWith(query) -> 540
            query in item.area -> 490
            query in item.address -> 440
            query in item.notes -> 280
            words.size > 1 && words.all {
                it in item.name || it in item.area || it in item.address
            } -> 400
            else -> 0
        }
        return maxOf(nameScore, phoneScore, otherScore)
    }

    /** Treat 07..., +9647..., 009647..., and 7... as the same Iraqi subscriber. */
    internal fun localIraqPhone(raw: String): String {
        val digits = normalizeDigits(raw)
        return when {
            digits.startsWith("009647") -> "0" + digits.removePrefix("00964")
            digits.startsWith("9647") -> "0" + digits.removePrefix("964")
            digits.length == 10 && digits.startsWith("7") -> "0$digits"
            else -> digits
        }
    }

    internal fun normalizeDigits(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '٠','۰' -> append('0')
                '١','۱' -> append('1')
                '٢','۲' -> append('2')
                '٣','۳' -> append('3')
                '٤','۴' -> append('4')
                '٥','۵' -> append('5')
                '٦','۶' -> append('6')
                '٧','۷' -> append('7')
                '٨','۸' -> append('8')
                '٩','۹' -> append('9')
                in '0'..'9' -> append(ch)
            }
        }
    }
    private data class Scored(val data: Indexed, val score: Int)
}

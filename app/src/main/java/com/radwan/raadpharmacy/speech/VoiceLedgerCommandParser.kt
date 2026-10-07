package com.radwan.raadpharmacy.speech

import com.radwan.raadpharmacy.data.Customer

enum class VoiceLedgerOperation {
    DEBT,
    PAYMENT
}

data class VoiceLedgerDraft(
    val operation: VoiceLedgerOperation,
    val amount: Long,
    val customerId: String?,
    val heardText: String
)

sealed interface VoiceLedgerParseResult {
    data class Success(val draft: VoiceLedgerDraft) : VoiceLedgerParseResult
    data class Error(val message: String) : VoiceLedgerParseResult
}

object VoiceLedgerCommandParser {
    private val debtWords = listOf("دين", "مديونيه", "اضف دين", "سجل دين")
    private val paymentWords = listOf(
        "تحصيل",
        "تحصيله",
        "تسديد",
        "سدد",
        "دفع",
        "استلمت",
        "استلام"
    )

    fun parse(
        alternatives: List<String>,
        customers: List<Customer>
    ): VoiceLedgerParseResult {
        val heard = alternatives.firstOrNull()?.trim().orEmpty()
        if (heard.isBlank()) {
            return VoiceLedgerParseResult.Error("لم أسمع أمرًا واضحًا. أعد المحاولة.")
        }

        val operation = detectOperation(heard)
            ?: return VoiceLedgerParseResult.Error(
                "قل «دين» أو «تحصيل/تسديد» ضمن الأمر الصوتي."
            )

        val amount = when (val parsed = ArabicDebtAmountParser.parseAlternatives(alternatives)) {
            is SpeechAmountParseResult.Success -> parsed.amount
            SpeechAmountParseResult.Ambiguous ->
                return VoiceLedgerParseResult.Error(
                    "سمعت أكثر من مبلغ محتمل. أعد نطق مبلغ واحد فقط."
                )
            SpeechAmountParseResult.NotFound ->
                return VoiceLedgerParseResult.Error(
                    "لم أتمكن من تحديد المبلغ. أعد المحاولة."
                )
        }

        val customer = matchCustomer(heard, customers)
        return VoiceLedgerParseResult.Success(
            VoiceLedgerDraft(
                operation = operation,
                amount = amount,
                customerId = customer?.id,
                heardText = heard
            )
        )
    }

    internal fun detectOperation(text: String): VoiceLedgerOperation? {
        val normalized = normalize(text)
        val paymentHit = paymentWords.any { containsPhrase(normalized, it) }
        val debtHit = debtWords.any { containsPhrase(normalized, it) }

        return when {
            paymentHit && !debtHit -> VoiceLedgerOperation.PAYMENT
            debtHit && !paymentHit -> VoiceLedgerOperation.DEBT
            paymentHit -> VoiceLedgerOperation.PAYMENT
            debtHit -> VoiceLedgerOperation.DEBT
            else -> null
        }
    }

    internal fun matchCustomer(
        text: String,
        customers: List<Customer>
    ): Customer? {
        if (customers.isEmpty()) return null
        val normalizedText = normalize(text)

        val exact = customers
            .map { it to normalize(it.name) }
            .filter { (_, name) -> name.isNotBlank() && normalizedText.contains(name) }
            .maxByOrNull { (_, name) -> name.length }
            ?.first
        if (exact != null) return exact

        val scored = customers.map { customer ->
            val tokens = normalize(customer.name)
                .split(' ')
                .filter { it.length >= 2 }
            val hits = tokens.count { token ->
                Regex("(^|\\s)" + Regex.escape(token) + "(\\s|$)")
                    .containsMatchIn(normalizedText)
            }
            customer to hits
        }.filter { it.second > 0 }

        val bestScore = scored.maxOfOrNull { it.second } ?: return null
        val best = scored.filter { it.second == bestScore }
        return best.singleOrNull()?.first
    }

    private fun containsPhrase(
        normalizedText: String,
        phrase: String
    ): Boolean {
        val normalizedPhrase = normalize(phrase)
        if (normalizedPhrase.isBlank()) return false
        return Regex(
            "(^|\\s)" + Regex.escape(normalizedPhrase) + "(\\s|$)"
        ).containsMatchIn(normalizedText)
    }

    private fun normalize(value: String): String =
        value
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
}

package com.radwan.raadpharmacy.speech

sealed interface SpeechAmountParseResult {
    data class Success(val amount: Long) : SpeechAmountParseResult
    data object Ambiguous : SpeechAmountParseResult
    data object NotFound : SpeechAmountParseResult
}

object ArabicDebtAmountParser {
    private val diacritics = Regex("[\u064B-\u065F\u0670]")
    private val punctuation = Regex("""[،؛:!?؟()\[\]{}"'ـ]""")
    private val numericLiteral = Regex("^[0-9]+$")

    private val simpleValues = mapOf(
        "صفر" to 0L,
        "واحد" to 1L,
        "واحده" to 1L,
        "احد" to 1L,
        "اثنين" to 2L,
        "اثنان" to 2L,
        "اثنا" to 2L,
        "اثني" to 2L,
        "اثنتين" to 2L,
        "اثنتان" to 2L,
        "ثلاثه" to 3L,
        "ثلاث" to 3L,
        "اربعه" to 4L,
        "اربع" to 4L,
        "خمسه" to 5L,
        "خمس" to 5L,
        "سته" to 6L,
        "ست" to 6L,
        "سبعه" to 7L,
        "سبع" to 7L,
        "ثمانيه" to 8L,
        "ثمان" to 8L,
        "تسعه" to 9L,
        "تسع" to 9L,
        "عشر" to 10L,
        "عشره" to 10L,
        "عشرون" to 20L,
        "عشرين" to 20L,
        "ثلاثون" to 30L,
        "ثلاثين" to 30L,
        "اربعون" to 40L,
        "اربعين" to 40L,
        "خمسون" to 50L,
        "خمسين" to 50L,
        "ستون" to 60L,
        "ستين" to 60L,
        "سبعون" to 70L,
        "سبعين" to 70L,
        "ثمانون" to 80L,
        "ثمانين" to 80L,
        "تسعون" to 90L,
        "تسعين" to 90L,
        "مئه" to 100L,
        "مائه" to 100L,
        "ميه" to 100L,
        "مئتين" to 200L,
        "مائتين" to 200L,
        "ميتين" to 200L,
        "ثلاثمئه" to 300L,
        "ثلاثمائه" to 300L,
        "ثلاثميه" to 300L,
        "اربعمئه" to 400L,
        "اربعمائه" to 400L,
        "اربعمية" to 400L,
        "اربعمـيه" to 400L,
        "خمس مئه" to 500L,
        "خمسمئه" to 500L,
        "خمسمائه" to 500L,
        "خمسميه" to 500L,
        "ستمئه" to 600L,
        "ستمائه" to 600L,
        "ستميه" to 600L,
        "سبعمئه" to 700L,
        "سبعمائه" to 700L,
        "سبعميه" to 700L,
        "ثمانمئه" to 800L,
        "ثمانمائه" to 800L,
        "ثمانميه" to 800L,
        "تسعمئه" to 900L,
        "تسعمائه" to 900L,
        "تسعميه" to 900L
    )

    private val thousandWords = setOf("الف", "الاف")
    private val thousandDualWords = setOf("الفين", "الفان")
    private val millionWords = setOf("مليون", "ملايين")
    private val millionDualWords = setOf("مليونين", "مليونان")

    fun hasCurrencyEndMarker(text: String): Boolean {
        val normalized = normalize(text).replace('.', ' ').trim()
        return Regex("(^|\\s)(دينار(?: عراقي)?|د\\s*ع)$").containsMatchIn(normalized)
    }

    fun parseAlternatives(texts: List<String>): SpeechAmountParseResult {
        if (texts.isEmpty()) return SpeechAmountParseResult.NotFound

        val parsed = texts
            .map(::parse)

        if (parsed.any { it is SpeechAmountParseResult.Ambiguous }) {
            return SpeechAmountParseResult.Ambiguous
        }

        val amounts = parsed
            .filterIsInstance<SpeechAmountParseResult.Success>()
            .map { it.amount }
            .filter { it > 0L }
            .distinct()

        return when {
            amounts.isEmpty() -> SpeechAmountParseResult.NotFound
            amounts.size == 1 -> SpeechAmountParseResult.Success(amounts.single())
            else -> SpeechAmountParseResult.Ambiguous
        }
    }

    fun parse(text: String): SpeechAmountParseResult {
        val normalized = normalize(text)
        if (normalized.isBlank()) return SpeechAmountParseResult.NotFound

        if (Regex("(^|\\s)او(\\s|$)").containsMatchIn(normalized)) {
            val alternatives = normalized.split(Regex("\\s+او\\s+"))
                .mapNotNull { parseSingleSide(it) }
                .distinct()
            return if (alternatives.size >= 2) {
                SpeechAmountParseResult.Ambiguous
            } else {
                alternatives.singleOrNull()
                    ?.takeIf { it > 0L }
                    ?.let(SpeechAmountParseResult::Success)
                    ?: SpeechAmountParseResult.NotFound
            }
        }

        val groups = numericGroups(normalized)
        if (groups.isEmpty()) return SpeechAmountParseResult.NotFound

        val values = groups.mapNotNull(::parseGroup)
            .filter { it > 0L }
            .distinct()

        return when {
            values.isEmpty() -> SpeechAmountParseResult.NotFound
            values.size == 1 -> SpeechAmountParseResult.Success(values.single())
            else -> SpeechAmountParseResult.Ambiguous
        }
    }

    private fun parseSingleSide(text: String): Long? {
        val values = numericGroups(text)
            .mapNotNull(::parseGroup)
            .filter { it > 0L }
            .distinct()
        return values.singleOrNull()
    }

    private fun numericGroups(normalized: String): List<List<String>> {
        val rawTokens = normalized
            .replace(",", "")
            .replace("٬", "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val tokens = buildList {
            rawTokens.forEach { token ->
                if (token == "و") {
                    add(token)
                } else if (isNumericToken(token)) {
                    add(token)
                } else if (token.startsWith("و") && token.length > 1) {
                    val remainder = token.drop(1)
                    if (isNumericToken(remainder)) {
                        add("و")
                        add(remainder)
                    } else {
                        add(token)
                    }
                } else {
                    add(token)
                }
            }
        }

        val groups = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()

        fun flush() {
            if (current.isNotEmpty()) {
                groups += current
                current = mutableListOf()
            }
        }

        tokens.forEachIndexed { index, token ->
            when {
                isNumericToken(token) -> current += token
                token == "و" -> {
                    val next = tokens.getOrNull(index + 1)
                    if (current.isEmpty() || next == null || !isNumericToken(next)) {
                        flush()
                    }
                }
                else -> flush()
            }
        }
        flush()

        return groups
    }

    private fun parseGroup(tokens: List<String>): Long? {
        if (tokens.isEmpty()) return null

        var total = 0L
        var group = 0L
        var sawNumber = false

        for (token in tokens) {
            if (token == "و") continue

            if (numericLiteral.matches(token)) {
                val literal = token.toLongOrNull() ?: return null
                group = safeAdd(group, literal)
                sawNumber = true
                continue
            }

            when {
                token in thousandDualWords -> {
                    total = safeAdd(total, if (group > 0L) safeMultiply(group, 2_000L) else 2_000L)
                    group = 0L
                    sawNumber = true
                }
                token in thousandWords -> {
                    total = safeAdd(total, safeMultiply(if (group > 0L) group else 1L, 1_000L))
                    group = 0L
                    sawNumber = true
                }
                token in millionDualWords -> {
                    total = safeAdd(total, if (group > 0L) safeMultiply(group, 2_000_000L) else 2_000_000L)
                    group = 0L
                    sawNumber = true
                }
                token in millionWords -> {
                    total = safeAdd(total, safeMultiply(if (group > 0L) group else 1L, 1_000_000L))
                    group = 0L
                    sawNumber = true
                }
                else -> {
                    val value = simpleValues[token] ?: return null
                    group = if (value == 100L && group in 1L..9L) {
                        safeMultiply(group, 100L)
                    } else {
                        safeAdd(group, value)
                    }
                    sawNumber = true
                }
            }
        }

        if (!sawNumber) return null
        return safeAdd(total, group)
    }

    private fun isNumericToken(token: String): Boolean =
        numericLiteral.matches(token) ||
            token in simpleValues ||
            token in thousandWords ||
            token in thousandDualWords ||
            token in millionWords ||
            token in millionDualWords

    private fun normalize(input: String): String {
        val latinDigits = buildString(input.length) {
            input.forEach { ch ->
                append(
                    when (ch) {
                        '٠', '۰' -> '0'
                        '١', '۱' -> '1'
                        '٢', '۲' -> '2'
                        '٣', '۳' -> '3'
                        '٤', '۴' -> '4'
                        '٥', '۵' -> '5'
                        '٦', '۶' -> '6'
                        '٧', '۷' -> '7'
                        '٨', '۸' -> '8'
                        '٩', '۹' -> '9'
                        else -> ch
                    }
                )
            }
        }

        return latinDigits
            .replace(diacritics, "")
            .replace(punctuation, " ")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace("٬", "")
            .replace(",", "")
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun safeAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

    private fun safeMultiply(a: Long, b: Long): Long =
        if (a != 0L && b > Long.MAX_VALUE / a) Long.MAX_VALUE else a * b
}

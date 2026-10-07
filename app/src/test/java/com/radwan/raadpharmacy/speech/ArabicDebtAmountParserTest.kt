package com.radwan.raadpharmacy.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicDebtAmountParserTest {

    @Test
    fun parsesFiveThousandArabicWords() {
        assertAmount("خمسة آلاف دينار عراقي", 5_000L)
    }

    @Test
    fun parsesSevenThousandFiveHundred() {
        assertAmount("سبعة آلاف وخمسمائة دينار عراقي", 7_500L)
    }

    @Test
    fun parsesTwentyThousandIraqiForm() {
        assertAmount("عشرين ألف دينار عراقي", 20_000L)
    }

    @Test
    fun parsesTwentyFiveThousand() {
        assertAmount("خمسة وعشرون ألف دينار عراقي", 25_000L)
    }

    @Test
    fun parsesEnglishAndArabicDigits() {
        assertAmount("5000 دينار عراقي", 5_000L)
        assertAmount("٥٠٠٠ دينار عراقي", 5_000L)
        assertAmount("5,000 دينار عراقي", 5_000L)
    }

    @Test
    fun commandWordsAndCustomerName_doNotChangeAmount() {
        assertAmount(
            "سجل دين على أحمد بمبلغ خمسة آلاف دينار عراقي",
            5_000L
        )
    }

    @Test
    fun alternativeAmounts_areRejectedAsAmbiguous() {
        assertEquals(
            SpeechAmountParseResult.Ambiguous,
            ArabicDebtAmountParser.parse(
                "خمسة آلاف أو ستة آلاف دينار عراقي"
            )
        )
    }

    @Test
    fun missingAmount_isNotGuessed() {
        assertEquals(
            SpeechAmountParseResult.NotFound,
            ArabicDebtAmountParser.parse("سجل دين على أحمد")
        )
    }

    @Test
    fun conflictingRecognitionAlternatives_areRejected() {
        assertEquals(
            SpeechAmountParseResult.Ambiguous,
            ArabicDebtAmountParser.parseAlternatives(
                listOf(
                    "خمسة آلاف دينار عراقي",
                    "ستة آلاف دينار عراقي"
                )
            )
        )
    }

    @Test
    fun currencyPhrase_isRecognizedAsEndMarker() {
        assertTrue(
            ArabicDebtAmountParser.hasCurrencyEndMarker(
                "خمسة آلاف دينار عراقي"
            )
        )
    }

    private fun assertAmount(text: String, expected: Long) {
        assertEquals(
            SpeechAmountParseResult.Success(expected),
            ArabicDebtAmountParser.parse(text)
        )
    }
}

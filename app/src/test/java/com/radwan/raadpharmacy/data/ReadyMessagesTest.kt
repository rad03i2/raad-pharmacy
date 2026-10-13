package com.radwan.raadpharmacy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyMessagesTest {
    @Test fun readableIraqiAmounts() {
        assertEquals("صفر دينار عراقي", IraqiAmountWords.format(0))
        assertEquals("خمسون ألف دينار عراقي", IraqiAmountWords.format(50_000))
        assertEquals("مئة وخمسة وعشرون ألف دينار عراقي", IraqiAmountWords.format(125_000))
        assertEquals("مليون دينار عراقي", IraqiAmountWords.format(1_000_000))
    }

    @Test fun replaceOnlyPlaceholdersWithCurrentValues() {
        val rendered = ReadyMessages.render(
            "{الزبون}: {المبلغ} ({المبلغ_كتابة}) بعد {الأيام} يومًا",
            "أحمد", 50_000, 30
        )
        assertTrue(rendered.contains("أحمد"))
        assertTrue(rendered.contains("50,000 دينار عراقي"))
        assertTrue(rendered.contains("خمسون ألف دينار عراقي"))
        assertTrue(rendered.contains("30 يومًا"))
    }

    @Test fun templateMatchesAgreedOverduePeriod() {
        val start = java.time.LocalDate.of(2026, 10, 1)
        val created = start.atStartOfDay(DebtFollowupEngine.IRAQ_ZONE).toInstant().toEpochMilli()
        val customer = Customer("customer", "أحمد", createdAt = created)
        val entries = listOf(LedgerEntry("entry", "customer", EntryType.DEBT, 50000L,
            createdAt = created, repaymentDays = 2))
        val account = DebtFollowupEngine.calculate(customer, entries, start.plusDays(3))
        assertEquals(ReadyMessages.Kind.TWO_DAYS, ReadyMessages.kindFor(account))
        val normal = DebtFollowupEngine.calculate(customer, entries, start.plusDays(2))
        assertEquals(ReadyMessages.Kind.NORMAL, ReadyMessages.kindFor(normal))
    }

    @Test fun durationSpecificDefaultsNeverMentionThirtyDays() {
        val early = listOf(ReadyMessages.Kind.ONE_DAY, ReadyMessages.Kind.TWO_DAYS,
            ReadyMessages.Kind.ONE_WEEK, ReadyMessages.Kind.TWO_WEEKS)
        early.forEach { kind ->
            assertTrue(kind.defaultText.contains("{المبلغ_كتابة}"))
            assertTrue(!kind.defaultText.contains("30 يوم"))
        }
    }
}

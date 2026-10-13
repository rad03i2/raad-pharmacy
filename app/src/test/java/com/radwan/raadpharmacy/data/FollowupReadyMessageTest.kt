package com.radwan.raadpharmacy.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FollowupReadyMessageTest {
    private val start = LocalDate.of(2026, 8, 1)
    private val stamp = start.atStartOfDay(DebtFollowupEngine.IRAQ_ZONE).toInstant().toEpochMilli()
    private val customer = Customer("message-test", "أحمد", createdAt = stamp)
    private lateinit var context: Context

    @Before fun reset() {
        context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("raad_ready_messages_v1", Context.MODE_PRIVATE).edit().clear().commit()
        ReadyMessages.Kind.entries.forEach { kind ->
            ReadyMessages.save(context, kind, "محفوظ ${kind.name}: {الزبون}، {المبلغ} ({المبلغ_كتابة})، {الأيام}")
        }
    }

    private fun debt(id: String = "debt", period: Int? = null, at: Long = stamp) =
        LedgerEntry(id, customer.id, EntryType.DEBT, 50_000L, createdAt = at, repaymentDays = period)

    @Test fun everySpecialAgreementUsesItsEditedTemplateAfterTheDueDate() {
        val periods = mapOf(1 to ReadyMessages.Kind.ONE_DAY, 2 to ReadyMessages.Kind.TWO_DAYS,
            7 to ReadyMessages.Kind.ONE_WEEK, 14 to ReadyMessages.Kind.TWO_WEEKS)
        periods.forEach { (days, kind) ->
            val account = DebtFollowupEngine.calculate(customer, listOf(debt(period = days)), start.plusDays(days + 1L))
            val message = ReadyMessages.messageFor(context, account)
            assertTrue(message.startsWith("محفوظ ${kind.name}: أحمد"))
            assertTrue(message.contains("50,000 دينار عراقي"))
            assertTrue(message.contains("خمسون ألف دينار عراقي"))
            val atDueDate = DebtFollowupEngine.calculate(customer, listOf(debt(period = days)), start.plusDays(days.toLong()))
            assertEquals(ReadyMessages.Kind.NORMAL, ReadyMessages.kindFor(atDueDate))
        }
    }

    @Test fun regularThirtyAndSixtyDayAccountsUseTheSavedMonthlyTemplates() {
        for ((age, kind) in listOf(30L to ReadyMessages.Kind.THIRTY_DAYS, 60L to ReadyMessages.Kind.SIXTY_DAYS,
            90L to ReadyMessages.Kind.SIXTY_DAYS)) {
            val account = DebtFollowupEngine.calculate(customer, listOf(debt()), start.plusDays(age))
            assertTrue(ReadyMessages.messageFor(context, account).startsWith("محفوظ ${kind.name}:"))
        }
    }

    @Test fun aSpecialAgreementKeepsItsOwnTemplateEvenWhenTheAccountIsOlderThanSixtyDays() {
        val account = DebtFollowupEngine.calculate(customer, listOf(debt(period = 1)), start.plusDays(90))
        assertTrue(ReadyMessages.messageFor(context, account).startsWith("محفوظ ONE_DAY:"))
    }

    @Test fun fifoPartialPaymentKeepsTheTemplateThenFullTranchePaymentSelectsTheNextAgreement() {
        val later = debt("later", 14, stamp + 10L * 86_400_000L)
        val partial = LedgerEntry("partial", customer.id, EntryType.PAYMENT, 10_000L, createdAt = stamp + 30L * 86_400_000L)
        val loans = listOf(debt(period = 1), later)
        val first = DebtFollowupEngine.calculate(customer, loans + partial, start.plusDays(35))
        val firstText = ReadyMessages.messageFor(context, first)
        assertTrue(firstText.startsWith("محفوظ ONE_DAY:"))
        assertTrue(firstText.contains("90,000 دينار عراقي"))
        val settle = partial.copy(id = "settle", amount = 40_000L, createdAt = partial.createdAt + 1)
        val next = DebtFollowupEngine.calculate(customer, loans + partial + settle, start.plusDays(35))
        val nextText = ReadyMessages.messageFor(context, next)
        assertTrue(nextText.startsWith("محفوظ TWO_WEEKS:"))
        assertTrue(nextText.contains("50,000 دينار عراقي"))
    }
}

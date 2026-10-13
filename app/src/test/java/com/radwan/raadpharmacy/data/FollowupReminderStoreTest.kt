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
class FollowupReminderStoreTest {
    private val start = LocalDate.of(2026, 8, 1)
    private val stamp = start.atStartOfDay(DebtFollowupEngine.IRAQ_ZONE).toInstant().toEpochMilli()
    private val customer = Customer("reminder-test", "زبون تجريبي", createdAt = stamp)
    private val debt = LedgerEntry("debt", customer.id, EntryType.DEBT, 50_000L, createdAt = stamp)
    private val markedAt = stamp + 40L * 86_400_000L
    private lateinit var context: Context
    private lateinit var store: FollowupReminderStore

    @Before fun reset() {
        context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("raad_followup_reminders_v1", Context.MODE_PRIVATE).edit().clear().commit()
        store = FollowupReminderStore(context)
    }

    private fun case(entries: List<LedgerEntry> = listOf(debt), age: Long = 31L): FollowupReminderStore.Case =
        FollowupReminderStore.caseFor(DebtFollowupEngine.calculate(customer, entries, start.plusDays(age)), entries)

    private fun mark(case: FollowupReminderStore.Case) {
        val request = store.stage(case)
        store.launched(request.token)
        store.departed()
        assertTrue(store.confirmSent(request.token, markedAt))
    }

    @Test fun stagingAndOpeningWhatsAppDoNotMarkTheCustomer() {
        val case = case()
        val request = store.stage(case)
        assertNull(store.confirmation())
        assertFalse(store.confirmSent(request.token, markedAt))
        store.launched(request.token)
        assertNull(store.confirmation())
        assertNull(store.confirmedAt(case))
        assertFalse(store.confirmSent(request.token, markedAt))
        store.departed()
        assertNotNull(store.confirmation())
        assertNull(store.confirmedAt(case))
    }

    @Test fun failedLaunchAndCanceledShareNeverMarkTheCustomer() {
        val case = case()
        val failed = store.stage(case)
        store.discard(failed.token)
        store.departed()
        assertNull(store.confirmation())
        assertNull(store.confirmedAt(case))
        val canceled = store.stage(case)
        store.launched(canceled.token)
        store.departed()
        store.discard(canceled.token)
        assertFalse(store.confirmSent(canceled.token, markedAt))
        assertNull(store.confirmedAt(case))
    }

    @Test fun pendingConfirmationAndConfirmedMarkSurviveReopeningTheStore() {
        val case = case()
        val request = store.stage(case)
        store.launched(request.token)
        store.departed()
        val reopened = FollowupReminderStore(context)
        assertEquals(request.token, reopened.confirmation()?.token)
        assertTrue(reopened.confirmSent(request.token, markedAt))
        assertEquals(markedAt, FollowupReminderStore(context).confirmedAt(case))
        assertNull(reopened.confirmation())
    }

    @Test fun partialPaymentKeepsTheMarkForOrdinaryAndSpecialFollowup() {
        for (period in listOf(null, 1)) {
            val loan = debt.copy(repaymentDays = period)
            val original = case(listOf(loan), 45)
            mark(original)
            val payment = LedgerEntry("payment", customer.id, EntryType.PAYMENT, 10_000L, createdAt = markedAt)
            val partial = case(listOf(loan, payment), 45)
            assertEquals(original, partial)
            assertEquals(markedAt, store.confirmedAt(partial))
        }
    }

    @Test fun newDebtFullSettlementAndSixtyDayEscalationDoNotReuseAnOldMark() {
        val original = case()
        mark(original)
        val extra = debt.copy(id = "extra", amount = 10_000L, createdAt = markedAt)
        assertNull(store.confirmedAt(case(listOf(debt, extra), 45)))
        val payment = LedgerEntry("settlement", customer.id, EntryType.PAYMENT, 50_000L, createdAt = markedAt)
        assertNull(store.confirmedAt(case(listOf(debt, payment), 45)))
        assertNull(store.confirmedAt(case(listOf(debt), 60)))
        assertEquals(markedAt, store.confirmedAt(original))
    }

    @Test fun aStaleConfirmationCannotMarkAnotherCustomersRequest() {
        val first = store.stage(case())
        store.launched(first.token)
        store.departed()
        val other = FollowupReminderStore.Case("another-customer", "another-case")
        val second = store.stage(other)
        store.launched(second.token)
        store.departed()
        assertFalse(store.confirmSent(first.token, markedAt))
        assertNull(store.confirmedAt(other))
        assertTrue(store.confirmSent(second.token, markedAt))
        assertEquals(markedAt, store.confirmedAt(other))
    }
}

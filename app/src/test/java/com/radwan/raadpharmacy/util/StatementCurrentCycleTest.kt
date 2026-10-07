package com.radwan.raadpharmacy.util

import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.data.LedgerEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatementCurrentCycleTest {

    @Test
    fun currentCycle_excludesOperationsBeforeLastFullSettlement() {
        val customer = Customer(id = "c1", name = "أحمد")
        val entries = listOf(
            LedgerEntry("d-old", "c1", EntryType.DEBT, 5_000L, createdAt = 1_000L),
            LedgerEntry("p-old", "c1", EntryType.PAYMENT, 5_000L, createdAt = 2_000L),
            LedgerEntry("d-current", "c1", EntryType.DEBT, 7_000L, createdAt = 3_000L),
            LedgerEntry("p-current", "c1", EntryType.PAYMENT, 2_000L, createdAt = 4_000L)
        )
        val snapshot = StatementSnapshot(
            customer = customer,
            entries = entries,
            currentBalance = 5_000L,
            totalDebts = 12_000L,
            totalPaid = 7_000L
        )

        assertEquals(
            listOf("p-current", "d-current"),
            StatementDocumentRenderer.currentCycleEntries(snapshot).map { it.id }
        )
    }

    @Test
    fun fullySettledAccount_hasNoCurrentCycle() {
        val customer = Customer(id = "c1", name = "أحمد")
        val entries = listOf(
            LedgerEntry("d1", "c1", EntryType.DEBT, 5_000L, createdAt = 1_000L),
            LedgerEntry("p1", "c1", EntryType.PAYMENT, 5_000L, createdAt = 2_000L)
        )
        val snapshot = StatementSnapshot(
            customer = customer,
            entries = entries,
            currentBalance = 0L,
            totalDebts = 5_000L,
            totalPaid = 5_000L
        )

        assertTrue(StatementDocumentRenderer.currentCycleEntries(snapshot).isEmpty())
    }
}

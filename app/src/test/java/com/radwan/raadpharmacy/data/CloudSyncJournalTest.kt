package com.radwan.raadpharmacy.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudSyncJournalTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("raad_cloud_sync_journal", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun repeatedMutation_isDeduplicated() {
        val journal = CloudSyncJournal(context)
        journal.markCustomerUpsert("customer-1")
        journal.markCustomerUpsert("customer-1")
        journal.markTransactionUpsert("transaction-1")
        journal.markTransactionUpsert("transaction-1")

        val pending = journal.snapshot()
        assertEquals(setOf("customer-1"), pending.customerUpserts)
        assertEquals(setOf("transaction-1"), pending.transactionUpserts)
    }

    @Test
    fun deleteAfterUpsert_deleteWinsConflict() {
        val journal = CloudSyncJournal(context)
        journal.markCustomerUpsert("customer-1")
        journal.markCustomerDelete("customer-1")
        journal.markTransactionUpsert("transaction-1")
        journal.markTransactionDelete("transaction-1")

        val pending = journal.snapshot()
        assertFalse("customer-1" in pending.customerUpserts)
        assertTrue("customer-1" in pending.customerDeletes)
        assertFalse("transaction-1" in pending.transactionUpserts)
        assertTrue("transaction-1" in pending.transactionDeletes)
    }

    @Test
    fun upsertAfterDelete_upsertWinsConflict() {
        val journal = CloudSyncJournal(context)
        journal.markCustomerDelete("customer-1")
        journal.markCustomerUpsert("customer-1")
        journal.markTransactionDelete("transaction-1")
        journal.markTransactionUpsert("transaction-1")

        val pending = journal.snapshot()
        assertTrue("customer-1" in pending.customerUpserts)
        assertFalse("customer-1" in pending.customerDeletes)
        assertTrue("transaction-1" in pending.transactionUpserts)
        assertFalse("transaction-1" in pending.transactionDeletes)
    }

    @Test
    fun pendingMutations_surviveRepositoryRecreation() {
        CloudSyncJournal(context).apply {
            markCustomerUpsert("customer-1")
            markTransactionDelete("transaction-1")
        }

        val restored = CloudSyncJournal(context).snapshot()
        assertEquals(setOf("customer-1"), restored.customerUpserts)
        assertEquals(setOf("transaction-1"), restored.transactionDeletes)
        assertFalse(restored.isEmpty)
    }

    @Test
    fun clearedMutation_doesNotReappear() {
        val journal = CloudSyncJournal(context)
        journal.markCustomerUpsert("customer-1")
        journal.clearCustomerUpsert("customer-1")

        assertTrue(journal.snapshot().isEmpty)
    }
}

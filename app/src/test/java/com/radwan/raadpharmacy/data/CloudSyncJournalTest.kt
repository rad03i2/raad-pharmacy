package com.radwan.raadpharmacy.cloud

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.data.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudSyncJournalTest {
    private lateinit var context: Context
    private lateinit var db: PharmacyLedgerDatabase
    private lateinit var dao: PharmacyLedgerDao
    private val customer = CustomerEntity("c1", "أحمد", null, "", "", 0, "", 1000)
    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("raad_cloud_sync_journal", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder<PharmacyLedgerDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
        dao = db.dao()
    }
    @After fun close() { db.close() }
    @Test fun repeatedMutation_isDeduplicated() = runTest {
        dao.insertCustomer(customer); dao.updateCustomer(customer.copy(name = "محمد"))
        assertEquals(1, dao.cloudOutbox().size)
        assertEquals(setOf("c1"), CloudSyncJournal(context, dao).snapshot().customerUpserts)
    }
    @Test fun deleteAfterUpsert_deleteWinsConflict() = runTest {
        dao.insertCustomer(customer); dao.deleteCustomerById("c1")
        val pending = CloudSyncJournal(context, dao).snapshot()
        assertTrue(pending.customerUpserts.isEmpty()); assertEquals(setOf("c1"), pending.customerDeletes)
    }
    @Test fun upsertAfterDelete_upsertWinsConflict() = runTest {
        dao.insertCustomer(customer); dao.deleteCustomerById("c1"); dao.insertCustomer(customer)
        val pending = CloudSyncJournal(context, dao).snapshot()
        assertEquals(setOf("c1"), pending.customerUpserts); assertTrue(pending.customerDeletes.isEmpty())
    }
    @Test fun pendingMutations_surviveRepositoryRecreation() = runTest {
        dao.insertCustomer(customer)
        assertEquals(CloudSyncJournal(context, dao).snapshot(), CloudSyncJournal(context, dao).snapshot())
        assertFalse(CloudSyncJournal(context, dao).snapshot().isEmpty)
    }
    @Test fun clearedMutation_doesNotReappear() = runTest {
        dao.insertCustomer(customer)
        val row = dao.cloudOutbox().single()
        dao.acknowledgeCloudMutation(row.key, row.revision)
        assertTrue(CloudSyncJournal(context, dao).snapshot().isEmpty)
    }
    @Test fun upgradeImportsLegacyPendingQueueBeforeAnyRemotePull() = runTest {
        dao.insertCustomer(customer, "REMOTE")
        context.getSharedPreferences("raad_cloud_sync_journal", Context.MODE_PRIVATE).edit()
            .putStringSet("customer_upserts", setOf("c1"))
            .putStringSet("transaction_deletes", setOf("old-debt")).commit()
        val journal = CloudSyncJournal(context, dao); journal.importLegacy()
        assertEquals(setOf("c1"), journal.snapshot().customerUpserts)
        assertEquals(setOf("old-debt"), journal.snapshot().transactionDeletes)
        dao.applyCloudSnapshot(emptyList(), emptyList()); assertNotNull(dao.getCustomerById("c1"))
        val row = dao.cloudOutbox().first { it.kind == "CUSTOMER" }
        dao.acknowledgeCloudMutation(row.key, row.revision); journal.importLegacy()
        assertTrue(journal.snapshot().customerUpserts.isEmpty())
    }
    @Test fun legacyImportCannotReplaceNewerDurableEdit() = runTest {
        dao.insertCustomer(customer)
        val before = dao.cloudOutbox()
        context.getSharedPreferences("raad_cloud_sync_journal", Context.MODE_PRIVATE).edit()
            .putStringSet("customer_deletes", setOf("c1")).commit()
        CloudSyncJournal(context, dao).importLegacy()
        assertEquals(before, dao.cloudOutbox())
    }
}

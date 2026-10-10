package com.radwan.raadpharmacy.data

import android.content.Context
import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.cloud.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineLedgerTest {
    private lateinit var app: Context
    private lateinit var db: PharmacyLedgerDatabase
    private lateinit var dao: PharmacyLedgerDao
    private lateinit var repository: AppRepository
    private val customer = CustomerEntity("c1", "أحمد", null, "", "", 0, "", 1000)
    private fun debt(id: String = "d1", amount: Long = 5000) =
        LedgerEntryEntity(id, "c1", "DEBT", amount, null, null, "دواء", 2000)
    @Before fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder<PharmacyLedgerDatabase>(app).setDriver(AndroidSQLiteDriver()).build()
        dao = db.dao()
        repository = AppRepository(app, dao) { throw IOException("scheduler/backup unavailable") }
    }
    @After fun close() { db.close() }

    @Test fun productionWriterUsesFullDiskSyncAcrossReopen() = runTest {
        val name = "offline-durability-mode.db"
        app.deleteDatabase(name)
        repeat(2) {
            val disk = Room.databaseBuilder<PharmacyLedgerDatabase>(app, name).setDriver(DurableLedgerDriver()).build()
            try {
                disk.dao().getCustomers()
                disk.dao().insertCustomer(customer)
                disk.useWriterConnection { connection -> connection.usePrepared("PRAGMA synchronous") {
                    assertTrue(it.step()); assertEquals(2L, it.getLong(0))
                } }
            } finally { disk.close() }
        }
        app.deleteDatabase(name)
    }

    @Test fun offlineCustomerDebtAndPaymentSucceedEvenWhenEverySchedulerFails() = runTest {
        val c = repository.addCustomer("أحمد", null, "الموصل", "", 0, "")
        val d = repository.addDebt(c.id, 10000, null, null) as DebtCreateResult.Created
        val p = repository.addPayment(c.id, 3000)
        assertNotNull(dao.getCustomerById(c.id))
        assertEquals(10000L, dao.getEntryById(d.entry.id)!!.amount)
        assertEquals("PAYMENT", dao.getEntryById(p.id)!!.type)
        assertEquals(3, dao.cloudOutbox().size)
        assertEquals(7000L, customerBalance(c, dao.getEntries().map { it.toModel() }))
    }
    @Test fun editsAndDeletesAreQueuedWithoutNetworkOrScheduling() = runTest {
        val c = repository.addCustomer("أحمد", null, "", "", 0, "")
        val d = (repository.addDebt(c.id, 5000, null, null) as DebtCreateResult.Created).entry
        assertTrue(repository.updateEntry(d.id, 7000, null, null, "تعديل").success)
        assertEquals(7000L, BackupJson.parse(dao.cloudOutbox().single { it.kind == "ENTRY" }.payload).entries.single().amount)
        assertTrue(repository.deleteEntry(d.id).success)
        assertTrue(repository.deleteCustomer(c.id).success)
        assertTrue(dao.cloudOutbox().all { it.action == "DELETE" })
    }
    @Test fun financialRowAndOutboxRollbackTogetherOnStorageFailure() = runTest {
        dao.insertCustomer(customer)
        db.useWriterConnection { connection -> connection.usePrepared("CREATE TRIGGER reject_queue BEFORE INSERT ON cloud_outbox BEGIN SELECT RAISE(ABORT, 'disk failure'); END") { it.step() } }
        assertThrows(Exception::class.java) { runBlocking { dao.insertEntry(debt()) } }
        assertNull(dao.getEntryById("d1"))
        assertEquals(1L, dao.latestBackupSequence())
        assertEquals(1, dao.cloudOutbox().size)
    }
    @Test fun committedFinancialRowsAndUploadsSurviveDatabaseCloseAndReopen() = runTest {
        val name = "offline-financial-restart.db"
        app.deleteDatabase(name)
        fun open() = Room.databaseBuilder<PharmacyLedgerDatabase>(app, name).setDriver(DurableLedgerDriver()).build()
        var disk = open()
        val r = AppRepository(app, disk.dao()) { throw IOException("process stopped before scheduling") }
        val c = r.addCustomer("أحمد", null, "", "", 0, "")
        r.addDebt(c.id, 10000, null, null); r.addPayment(c.id, 2000)
        val expected = disk.dao().cloudOutbox()
        disk.close(); disk = open()
        try {
            assertEquals(1, disk.dao().customerCount()); assertEquals(2, disk.dao().entryCount())
            assertEquals(expected, disk.dao().cloudOutbox())
            val sent = mutableListOf<CloudOutboxEntity>()
            DurableCloudDrain(disk.dao()) { sent.add(it) }.flush()
            assertEquals(3, sent.size); assertEquals("CUSTOMER", sent.first().kind)
            assertTrue(disk.dao().cloudOutbox().isEmpty())
        } finally { disk.close(); app.deleteDatabase(name) }
    }
    @Test fun failedOfflineUploadStaysQueuedAndReconnectDrainsInDependencyOrder() = runTest {
        dao.insertCustomer(customer); dao.insertEntry(debt())
        assertThrows(IOException::class.java) { runBlocking { DurableCloudDrain(dao) { throw IOException("offline") }.flush() } }
        assertEquals(2, dao.cloudOutbox().size)
        val sent = mutableListOf<String>()
        DurableCloudDrain(dao) { sent.add(it.kind) }.flush()
        assertEquals(listOf("CUSTOMER", "ENTRY"), sent); assertTrue(dao.cloudOutbox().isEmpty())
    }
    @Test fun responseLostAfterServerCommitRetriesSameIdWithoutDuplicateOperation() = runTest {
        dao.insertCustomer(customer); dao.insertEntry(debt())
        val server = mutableMapOf<String, String>(); val notifications = mutableSetOf<String>()
        var lost = true
        val send: suspend (CloudOutboxEntity) -> Unit = { row ->
            if (server.put(row.key, row.payload) != row.payload && row.kind == "ENTRY") notifications.add(row.entityId)
            if (row.kind == "ENTRY" && lost) { lost = false; throw IOException("response lost") }
        }
        assertThrows(IOException::class.java) { runBlocking { DurableCloudDrain(dao, send).flush() } }
        assertEquals(1, dao.cloudOutbox().size)
        DurableCloudDrain(dao, send).flush()
        assertEquals(2, server.size); assertEquals(setOf("d1"), notifications)
        assertTrue(dao.cloudOutbox().isEmpty())
    }
    @Test fun concurrentEditWhileUploadIsInFlightCannotBeAcknowledgedByOldResponse() = runTest {
        dao.insertCustomer(customer); dao.insertEntry(debt())
        DurableCloudDrain(dao) { row -> if (row.kind == "ENTRY") dao.updateEntry(debt(amount = 9000)) }.flush()
        assertEquals(9000L, BackupJson.parse(dao.cloudOutbox().single().payload).entries.single().amount)
        assertEquals(9000L, dao.getEntryById("d1")!!.amount)
        DurableCloudDrain(dao) {}.flush(); assertTrue(dao.cloudOutbox().isEmpty())
    }
    @Test fun remoteSnapshotAndRealtimeCannotErasePendingOfflineChanges() = runTest {
        dao.insertCustomer(customer); dao.insertEntry(debt())
        dao.applyCloudSnapshot(emptyList(), emptyList())
        dao.applyCloudCustomer("c1", null); dao.applyCloudEntry("d1", null)
        dao.applyCloudEntry("d1", debt(amount = 1))
        assertEquals(customer, dao.getCustomerById("c1")); assertEquals(debt(), dao.getEntryById("d1"))
        assertEquals(2, dao.cloudOutbox().size)
    }
    @Test fun pendingEntryProtectsItsAlreadySyncedParentFromRemoteDeletion() = runTest {
        dao.insertCustomer(customer, "REMOTE"); dao.insertEntry(debt())
        dao.applyCloudCustomer("c1", null); dao.applyCloudSnapshot(emptyList(), emptyList())
        assertNotNull(dao.getCustomerById("c1")); assertNotNull(dao.getEntryById("d1"))
    }
    @Test fun remoteChangesAndBackupPruningCannotCreateOrRemoveUploadRequests() = runTest {
        dao.insertCustomer(customer, "REMOTE"); dao.insertEntry(debt(), "REMOTE")
        assertTrue(dao.cloudOutbox().isEmpty())
        dao.updateEntry(debt(amount = 8000)); val before = dao.cloudOutbox()
        dao.pruneBackupChanges(dao.latestBackupSequence())
        assertEquals(before, dao.cloudOutbox())
    }
    @Test fun workerCancellationLeavesUnacknowledgedUploadDurable() = runTest {
        dao.insertCustomer(customer)
        assertThrows(CancellationException::class.java) { runBlocking { DurableCloudDrain(dao) { throw CancellationException() }.flush() } }
        assertEquals(1, dao.cloudOutbox().size)
    }
    @Test fun restoreHoldNeverUploadsArchivedOperationsAndGivesActionableSaveError() = runTest {
        dao.insertCustomer(customer); dao.insertEntry(debt())
        dao.restoreLocal(listOf(customer), listOf(debt()), emptyList())
        assertTrue(dao.cloudOutbox().isEmpty())
        var sent = false; DurableCloudDrain(dao) { sent = true }.flush(); assertFalse(sent)
        val failure = assertThrows(IllegalStateException::class.java) { runBlocking { dao.insertEntry(debt("blocked")) } }
        assertTrue(ledgerSaveError(failure, "عام").contains("التخزين والنسخ الاحتياطي"))
    }
}

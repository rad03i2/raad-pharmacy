package com.radwan.raadpharmacy.data

import android.content.Context
import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.cloud.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.io.File
import org.json.JSONObject

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

    @Test fun versionTwoMigrationPreservesLedgerBackupStateAndPendingLegacyWork() = runTest {
        val name = "offline-migration-v2.db"
        app.deleteDatabase(name); app.getDatabasePath(name).parentFile!!.mkdirs()
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name), null)
        val entities = JSONObject(File("schemas/com.radwan.raadpharmacy.data.PharmacyLedgerDatabase/2.json").readText())
            .getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            fun sql(template: String) = template.replace("\${TABLE_NAME}", entity.getString("tableName"))
            old.execSQL(sql(entity.getString("createSql")))
            entity.optJSONArray("indices")?.let { indices ->
                for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
        }
        old.execSQL("INSERT INTO customers VALUES ('c1', 'أحمد', NULL, '', '', 0, '', 1000)")
        old.execSQL("INSERT INTO ledger_entries VALUES ('existing', 'c1', 'DEBT', 5000, NULL, NULL, '', 2000)")
        old.execSQL("INSERT INTO backup_control VALUES ('watermark', '51')")
        old.execSQL("INSERT INTO sqlite_sequence (name, seq) VALUES ('backup_changes', 51)")
        old.version = 2; old.close()
        app.getSharedPreferences("raad_cloud_sync_journal", Context.MODE_PRIVATE).edit().clear()
            .putStringSet("transaction_upserts", setOf("existing")).commit()
        val migrated = Room.databaseBuilder<PharmacyLedgerDatabase>(app, name).setDriver(DurableLedgerDriver())
            .addMigrations(CloudOutboxMigration.MIGRATION_2_3, PharmacyLedgerDatabase.MIGRATION_3_4).build()
        try {
            val d = migrated.dao()
            assertEquals(1, d.customerCount()); assertEquals(1, d.entryCount()); assertEquals(51L, d.latestBackupSequence())
            assertTrue(d.cloudOutbox().isEmpty())
            CloudSyncJournal(app, d).importLegacy()
            assertEquals("existing", d.cloudOutbox().single().entityId)
            d.applyCloudSnapshot(emptyList(), emptyList()); assertEquals(1, d.entryCount())
            d.insertEntry(debt("after-migration")); assertEquals(52L, d.latestBackupSequence())
        } finally { migrated.close(); app.deleteDatabase(name) }
    }

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

    @Test fun retryingTheSameCustomerDraftDoesNotInsertASecondCustomer() = runTest {
        val operationId = java.util.UUID.randomUUID().toString()
        val first = repository.addCustomer(
            "أحمد", "07701234567", "الموصل", "", 0, "", operationId
        )
        val retry = repository.addCustomer(
            "أحمد", "07701234567", "الموصل", "", 0, "", operationId
        )

        assertEquals(first.id, retry.id)
        assertEquals(operationId, retry.id)
        assertEquals(1, dao.customerCount())
        assertEquals(1, dao.cloudOutbox().count { it.kind == "CUSTOMER" && it.entityId == operationId })
    }

    @Test fun retryingTheSameDebtDraftDoesNotInsertASecondDebt() = runTest {
        val c = repository.addCustomer("أحمد", null, "الموصل", "", 0, "")
        val operationId = java.util.UUID.randomUUID().toString()
        val first = repository.addDebt(
            c.id, 5000, null, null, details = "أدوية", operationId = operationId
        ) as DebtCreateResult.Created
        val retry = repository.addDebt(
            c.id, 5000, null, null, details = "أدوية", operationId = operationId
        ) as DebtCreateResult.Created

        assertEquals(first.entry.id, retry.entry.id)
        assertEquals(1, dao.getEntriesForCustomer(c.id).count { it.type == "DEBT" })
        assertEquals(1, dao.cloudOutbox().count { it.kind == "ENTRY" && it.entityId == operationId })
    }

    @Test fun retryingTheSamePaymentDraftDoesNotCollectTwice() = runTest {
        val c = repository.addCustomer("أحمد", null, "الموصل", "", 10000, "")
        val operationId = java.util.UUID.randomUUID().toString()
        val first = repository.addPayment(c.id, 6000, operationId)
        val retry = repository.addPayment(c.id, 6000, operationId)

        assertEquals(first.id, retry.id)
        assertEquals(1, dao.getEntriesForCustomer(c.id).count { it.type == "PAYMENT" })
        assertEquals(4000L, customerBalance(c, dao.getEntriesForCustomer(c.id).map { it.toModel() }))
        assertEquals(1, dao.cloudOutbox().count { it.kind == "ENTRY" && it.entityId == operationId })
    }
    @Test fun concurrentPaymentsCannotExceedTheDurableBalance() = runTest {
        dao.insertCustomer(customer.copy(openingDebt = 10000))
        val attempts = listOf("p1", "p2").map { id -> async(Dispatchers.IO) {
            runCatching { dao.insertPaymentProtected(debt(id, 6000).copy(type = "PAYMENT")) }.isSuccess
        } }.awaitAll()
        assertEquals(1, attempts.count { it }); assertEquals(1, dao.entryCount())
        assertEquals(2, dao.cloudOutbox().size)
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

    @Test fun deletingMovementRemovesItFromLedgerBalanceAndQueuedSyncEvenWithStaleCloudSnapshot() = runTest {
        val c = repository.addCustomer("عميل", null, "", "", 0, "")
        val d = (repository.addDebt(c.id, 5000L, 2, 2500L) as DebtCreateResult.Created).entry
        val p = repository.addPayment(c.id, 2000L)
        assertEquals(3000L, customerBalance(c, dao.getEntriesForCustomer(c.id).map { it.toModel() }))

        assertTrue(repository.deleteEntry(d.id).success)
        assertNull(dao.getEntryById(d.id))
        assertEquals(0L, customerBalance(c, dao.getEntriesForCustomer(c.id).map { it.toModel() }))
        assertEquals(listOf(p.id), dao.getEntriesForCustomer(c.id).map { it.id })
        assertEquals("DELETE", dao.cloudOutbox().single { it.kind == "ENTRY" && it.entityId == d.id }.action)

        // A stale remote snapshot cannot resurrect a locally deleted financial row.
        dao.applyCloudSnapshot(listOf(c.toEntity()), listOf(d.toEntity(), p.toEntity()))
        assertNull(dao.getEntryById(d.id))
        assertEquals(0L, customerBalance(c, dao.getEntriesForCustomer(c.id).map { it.toModel() }))
        assertTrue(repository.updateCustomer(c.id, "عميل", "07701234567", "", "", 0, "").success)

        // The payment stays intact and is credited toward any later debit; never invent debt.
        val newDebt = (repository.addDebt(c.id, 6000L, null, null) as DebtCreateResult.Created).entry
        assertTrue(repository.updateEntry(newDebt.id, 4000L, null, null, "").success)
        assertEquals(2000L, customerBalance(c, dao.getEntriesForCustomer(c.id).map { it.toModel() }))
    }

    @Test fun editingDebtDoesNotEraseBottleMetadataOrAgreedReminder() = runTest {
        val c = repository.addCustomer("مريض", null, "", "", 0, "")
        val d = (repository.addDebt(c.id, 5000L, 2, 2500L,
            details = "صيدلية", repaymentDays = 7) as DebtCreateResult.Created).entry
        assertTrue(repository.updateEntry(d.id, 6000L, null, null, "تعديل المشتريات").success)
        val actual = dao.getEntryById(d.id)!!.toModel()
        assertEquals(2, actual.bottles)
        assertEquals(2500L, actual.bottlePrice)
        assertEquals(7, actual.repaymentDays)
        assertEquals("تعديل المشتريات", actual.details)
    }

    @Test fun settledCustomerWithHistoricalDebtAndPaymentCanBeDeleted() = runTest {
        val c = repository.addCustomer("حساب مسدد", null, "", "", 0, "")
        repository.addDebt(c.id, 5000, null, null)
        repository.addPayment(c.id, 5000)
        assertEquals(0L, customerBalance(c, dao.getEntries().map { it.toModel() }))
        assertTrue(repository.deleteCustomer(c.id).success)
        assertNull(dao.getCustomerById(c.id))
        assertTrue(dao.getEntriesForCustomer(c.id).isEmpty())
    }

    @Test fun customerCreatorNameSurvivesBackupRoundTrip() {
        val customer = Customer("creator-c", "زبون", createdByName = "الصيدلي")
        val restored = BackupJson.parse(
            BackupJson.encode(listOf(customer), emptyList(), "3.3.19.6")
        ).customers.single()
        assertEquals("الصيدلي", restored.createdByName)
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
    @Test fun severalBlockedDebtsRemainDurableWhileAnUnrelatedReceiptStillUploads() = runTest {
        dao.insertCustomer(customer, "REMOTE")
        repeat(5) { dao.insertEntry(debt(id = "blocked-$it")) }
        dao.insertEntry(LedgerEntryEntity("receipt", "c1", "PAYMENT", 1000, null, null, "", 3000))
        val expected=dao.cloudOutbox().filter { it.entityId.startsWith("blocked-") }
        val sent=mutableListOf<String>()
        val result=runCatching { DurableCloudDrain(dao) { row ->
            if(row.entityId.startsWith("blocked-")) throw IllegalStateException("CUSTOMER_CREDIT_BLOCKED")
            sent+=row.entityId
        }.flush() }
        assertTrue(result.isFailure);assertEquals(listOf("receipt"),sent)
        assertEquals(expected,dao.cloudOutbox())
        DurableCloudDrain(dao) {}.flush();assertTrue(dao.cloudOutbox().isEmpty())
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
    @Test fun authorConfirmationFailureRetainsUploadAndRetryUsesOneStableNotificationId() = runTest {
        dao.insertCustomer(customer, "REMOTE"); dao.insertEntry(debt())
        val serverIds = mutableSetOf<String>(); val notices = mutableSetOf<String>()
        var fail = true
        val send: suspend (CloudOutboxEntity) -> Unit = { row ->
            serverIds.add(row.entityId)
            if (fail) { fail = false; throw IOException("notification interrupted before acknowledgement") }
            notices.add(CloudUploadFeedback.eventId(row))
        }
        assertThrows(IOException::class.java) { runBlocking { DurableCloudDrain(dao, send).flush() } }
        assertEquals(1, dao.cloudOutbox().size)
        val id = CloudUploadFeedback.eventId(dao.cloudOutbox().single())
        DurableCloudDrain(dao, send).flush()
        assertEquals(setOf("d1"), serverIds); assertEquals(setOf(id), notices)
        assertTrue(dao.cloudOutbox().isEmpty())
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

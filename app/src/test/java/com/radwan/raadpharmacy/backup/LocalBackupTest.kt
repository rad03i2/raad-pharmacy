package com.radwan.raadpharmacy.backup

import android.content.Context
import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalBackupTest {
    private lateinit var app: Context
    private lateinit var db: PharmacyLedgerDatabase
    private lateinit var dao: PharmacyLedgerDao
    private val customer = CustomerEntity("c1", "أحمد", null, "", "", 0, "", 1000)
    private val key = BackupCrypto.newKey()
    @Before fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "local_backups_v2").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder<PharmacyLedgerDatabase>(app).setDriver(AndroidSQLiteDriver()).build()
        dao = db.dao()
    }
    @After fun close() { db.close(); File(app.filesDir, "local_backups_v2").deleteRecursively() }
    private fun debt(id: String, amount: Long = 5000, time: Long = 2000) = LedgerEntryEntity(id, customer.id, "DEBT", amount, null, null, "أدوية", time)

    @Test fun hundredFinancialWritesHaveOrderedPersistentProtection() = runTest {
        dao.insertCustomer(customer)
        val start = System.nanoTime()
        repeat(100) { dao.insertEntry(debt("d$it", time = 2000L + it)) }
        val elapsed = (System.nanoTime() - start) / 1_000_000
        println("BACKUP_BENCHMARK_100_WRITES_MS=$elapsed (Robolectric; not a device battery benchmark)")
        val rows = dao.backupChanges(0, 200)
        assertEquals(101, rows.size)
        assertEquals((1L..101L).toList(), rows.map { it.sequence })
        assertEquals(101, rows.map { it.eventId }.toSet().size)
        assertEquals(100, dao.entryCount())
    }
    @Test fun concurrentBurstDoesNotLoseChanges() = runTest {
        dao.insertCustomer(customer)
        (1..30).map { async(Dispatchers.IO) { dao.insertEntry(debt("b$it")) } }.awaitAll()
        assertEquals(30, dao.entryCount()); assertEquals(31L, dao.latestBackupSequence())
    }
    @Test fun failedFinancialTransactionRollsBackJournalAsWell() = runTest {
        dao.insertCustomer(customer)
        val before = dao.latestBackupSequence()
        assertThrows(Exception::class.java) { runBlocking {
            dao.replaceAll(listOf(customer.copy(id = "other")), listOf(debt("orphan")))
        } }
        assertEquals(before, dao.latestBackupSequence()); assertEquals(customer, dao.getCustomerById("c1"))
    }
    @Test fun journalFailureRollsBackAnOtherwiseValidDebt() = runTest {
        dao.insertCustomer(customer)
        // Simulate storage/journal rejection; the financial insert must share its transaction.
        db.useWriterConnection { connection -> connection.usePrepared("CREATE TRIGGER reject_journal BEFORE INSERT ON backup_changes BEGIN SELECT RAISE(ABORT, 'journal unavailable'); END") { it.step() } }
        assertThrows(Exception::class.java) { runBlocking { dao.insertEntry(debt("rejected")) } }
        assertNull(dao.getEntryById("rejected")); assertEquals(1L, dao.latestBackupSequence())
    }
    @Test fun snapshotAndIncrementalsRestoreEditsDeletesPhotosAndRemoteWritesExactlyOnce() = runTest {
        dao.insertCustomer(customer)
        val snapshot = BackupArchive.snapshot(dao.captureBackup(), "chain", JSONObject(), 1500)
        dao.insertEntry(debt("a")); dao.updateEntry(debt("a", 9000))
        dao.insertEntry(debt("b"), "REMOTE"); dao.deleteEntryById("a")
        dao.setBackupPhoto("c1", byteArrayOf(1, 2, 3))
        val segment = BackupArchive.changes(dao.backupChanges(snapshot.getLong("sequence")), "chain", JSONObject())
        val restored = BackupArchive.restore(snapshot, listOf(segment, segment), dao.latestBackupSequence())
        assertEquals(listOf("b"), restored.ledger.entries.map { it.id })
        assertArrayEquals(byteArrayOf(1, 2, 3), restored.photos.single().bytes)
        assertTrue(dao.backupChanges(0).any { it.origin == "REMOTE" })
    }
    @Test fun missingOrConflictingSegmentIsRejected() = runTest {
        dao.insertCustomer(customer)
        val snapshot = BackupArchive.snapshot(dao.captureBackup(), "chain", JSONObject(), 1500)
        dao.insertEntry(debt("a")); dao.insertEntry(debt("b"))
        val rows = dao.backupChanges(snapshot.getLong("sequence"))
        val part = BackupArchive.changes(rows.drop(1), "chain", JSONObject())
        assertThrows(Exception::class.java) { BackupArchive.restore(snapshot, listOf(part), 3) }
        val good = BackupArchive.changes(rows, "chain", JSONObject())
        val bad = JSONObject(good.toString()).apply { getJSONArray("events").getJSONObject(0).put("eventId", "different") }
        assertThrows(Exception::class.java) { BackupArchive.restore(snapshot, listOf(good, bad), 3) }
    }
    @Test fun portableEncryptionRejectsWrongKeyTamperingAndTruncation() {
        val raw = "بيانات مالية خاصة".toByteArray(Charsets.UTF_8)
        val encrypted = BackupCrypto.seal(raw, key)
        assertArrayEquals(raw, BackupCrypto.open(encrypted, BackupCrypto.parseCode(BackupCrypto.recoveryCode(key))))
        assertThrows(Exception::class.java) { BackupCrypto.open(encrypted, BackupCrypto.newKey()) }
        val changed = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { BackupCrypto.open(changed, key) }
        assertThrows(Exception::class.java) { BackupCrypto.open(encrypted.copyOf(encrypted.size - 1), key) }
    }
    @Test fun removedDestinationCannotAdvanceCursorOrPrunePendingChanges() = runTest {
        dao.insertCustomer(customer)
        val engine = LocalBackupEngine.forTesting(app, dao, key)
        assertTrue(engine.process())
        dao.saveBackupDestination(BackupDestinationEntity("sd", treeUri = "content://missing/tree/card", lastFullAt = 1000))
        dao.insertEntry(debt("pending"))
        assertFalse(engine.process())
        val state = dao.backupDestinations().single { it.id == "sd" }
        assertEquals(0L, state.cursor); assertNotNull(state.error)
        assertTrue(dao.backupChanges(0).any { it.entityId == "pending" })
        assertEquals(dao.latestBackupSequence(), dao.backupDestinations().single { it.id == "private" }.cursor)
    }
    @Test fun privateEngineRestartsDrainsJournalAndDoesNotOverwriteGoodSnapshot() = runTest {
        dao.insertCustomer(customer)
        var engine = LocalBackupEngine.forTesting(app, dao, key)
        assertTrue(engine.process())
        val first = engine.history().single()
        dao.insertEntry(debt("after-crash"))
        engine = LocalBackupEngine.forTesting(app, dao, key)
        assertTrue(engine.process())
        assertEquals(1, engine.previewHistory(first).ledger.entries.size)
        assertEquals(2L, dao.latestBackupSequence()) // watermark persists even after queue cleanup.
        val snapshots = File(app.filesDir, "local_backups_v2/Snapshots").listFiles()!!.size
        assertEquals(1, snapshots) // No complete DB copy for every debt.
    }
    @Test fun failedRestoreLeavesHoldAndLedgerUnchangedSuccessfulRestoreBlocksWrites() = runTest {
        dao.insertCustomer(customer)
        val before = dao.latestBackupSequence()
        assertThrows(Exception::class.java) { runBlocking { dao.restoreLocal(listOf(customer.copy(id = "new")), listOf(debt("orphan")), emptyList()) } }
        assertNull(dao.restoreHold()); assertEquals(before, dao.latestBackupSequence())
        dao.restoreLocal(listOf(customer), listOf(debt("restored")), emptyList())
        assertEquals("1", dao.restoreHold())
        assertThrows(Exception::class.java) { runBlocking { dao.insertEntry(debt("should-not-upload")) } }
        assertNull(dao.getEntryById("should-not-upload"))
        dao.completeReconciliation(listOf(customer), emptyList())
        assertEquals("0", dao.restoreHold()); assertEquals(0, dao.entryCount())
        dao.insertEntry(debt("new-real-write")); assertEquals(1, dao.entryCount())
    }
    @Test fun fileBackedJournalSurvivesCloseAndReopenWithoutInternet() = runTest {
        val name = "backup-journal-restart-test.db"
        app.deleteDatabase(name)
        fun open() = Room.databaseBuilder<PharmacyLedgerDatabase>(app, name).setDriver(AndroidSQLiteDriver()).build()
        var disk = open()
        disk.dao().insertCustomer(customer); disk.dao().insertEntry(debt("durable")); disk.close()
        disk = open()
        assertEquals(2L, disk.dao().latestBackupSequence())
        assertEquals(2, disk.dao().backupChanges(0).size)
        disk.close(); app.deleteDatabase(name)
    }
    @Test fun versionOneMigrationPreservesExistingCustomersAndFinancialRows() = runTest {
        val name = "backup-migration-test.db"
        app.deleteDatabase(name)
        app.getDatabasePath(name).parentFile!!.mkdirs()
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name), null)
        old.execSQL("CREATE TABLE customers (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, phone TEXT, area TEXT NOT NULL, address TEXT NOT NULL, opening_debt INTEGER NOT NULL, notes TEXT NOT NULL, created_at INTEGER NOT NULL)")
        old.execSQL("CREATE TABLE ledger_entries (id TEXT NOT NULL PRIMARY KEY, customer_id TEXT NOT NULL, type TEXT NOT NULL, amount INTEGER NOT NULL, bottles INTEGER, bottle_price INTEGER, details TEXT NOT NULL, created_at INTEGER NOT NULL, FOREIGN KEY(customer_id) REFERENCES customers(id) ON DELETE CASCADE)")
        listOf("name", "area", "created_at").forEach { old.execSQL("CREATE INDEX index_customers_$it ON customers($it)") }
        listOf("customer_id", "created_at", "type").forEach { old.execSQL("CREATE INDEX index_ledger_entries_$it ON ledger_entries($it)") }
        old.execSQL("INSERT INTO customers VALUES ('c1', 'أحمد', NULL, '', '', 0, '', 1000)")
        old.execSQL("INSERT INTO ledger_entries VALUES ('old-debt', 'c1', 'DEBT', 5000, NULL, NULL, '', 2000)")
        old.version = 1
        old.close()
        val migrated = Room.databaseBuilder<PharmacyLedgerDatabase>(app, name).setDriver(AndroidSQLiteDriver())
            .addMigrations(BackupMigration.MIGRATION_1_2).build()
        assertEquals(1, migrated.dao().customerCount()); assertEquals(1, migrated.dao().entryCount())
        migrated.dao().insertEntry(debt("new-after-migration"))
        assertEquals(1, migrated.dao().backupChanges(0).size)
        migrated.close(); app.deleteDatabase(name)
    }
    @Test fun fullSnapshotDuringConcurrentWritesKeepsEarlierRestoreChainContinuous() = runTest {
        dao.insertCustomer(customer)
        val engine = LocalBackupEngine.forTesting(app, dao, key)
        assertTrue(engine.process())
        val earlier = engine.history().single()
        val writer = async(Dispatchers.IO) { repeat(100) { dao.insertEntry(debt("during-$it")) } }
        engine.process(force = true)
        writer.await(); assertTrue(engine.process())
        assertEquals(100, engine.previewHistory(earlier).ledger.entries.size)
    }
    @Test fun corruptIncrementalFileIsRejectedBeforeRestore() = runTest {
        dao.insertCustomer(customer)
        val engine = LocalBackupEngine.forTesting(app, dao, key)
        assertTrue(engine.process())
        val first = engine.history().single()
        dao.insertEntry(debt("later")); assertTrue(engine.process())
        val segment = File(app.filesDir, "local_backups_v2/Changes").listFiles()!!.single()
        val bytes = segment.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); segment.writeBytes(bytes)
        assertThrows(Exception::class.java) { runBlocking { engine.previewHistory(first) } }
        assertEquals(1, dao.entryCount()); assertNull(dao.restoreHold())
    }
    @Test fun retentionKeepsRecentDailyWeeklyWithoutDuplicateFiles() {
        val day = 86400000L
        val rows = (0..60).flatMap { d -> (0..3).map { h -> "${d}_$h" to (1700000000000L - d * day - h * 21600000L) } }
        val kept = BackupRetention.keep(rows)
        assertTrue(rows.take(4).all { it.first in kept })
        assertTrue(kept.size <= 15); assertTrue(kept.size >= 7)
    }
}

package com.radwan.raadpharmacy.backup

import android.content.Context
import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.cloud.*
import com.radwan.raadpharmacy.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryTest {
    private lateinit var db: PharmacyLedgerDatabase
    private lateinit var dao: PharmacyLedgerDao
    private lateinit var app: Context
    private val owner = id(90)
    private val pharmacy = id(91)
    private val customer = CustomerEntity(id(1), "زبون اختبار", "07000000000", "", "", 0, "", 1000)
    private fun debt(n: Int, amount: Long, time: Long = n * 1000L) =
        LedgerEntryEntity(id(n), customer.id, "DEBT", amount, null, null, "", time)
    private fun archive(entries: List<LedgerEntryEntity>, customers: List<CustomerEntity> = listOf(customer)) =
        RestoredArchive(BackupPayload(customers.map { it.toModel() }, entries.map { it.toModel() }, 10000, 1),
            emptyList(), JSONObject().put("synced", true), 0)
    private fun importer(safety: suspend () -> Unit = {}) = RecoveryImporter(dao, safety)
    @Before fun setup() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder<PharmacyLedgerDatabase>(app).setDriver(AndroidSQLiteDriver()).build()
        dao = db.dao()
        dao.insertCustomer(customer, "REMOTE")
        dao.insertEntry(debt(2, 5_000_000), "REMOTE")
    }
    @After fun close() { db.close() }

    @Test fun fiveToNineMillionRecoversFiveMillionDebtAndOneMillionPaymentNotAnInventedFourMillionDebt() = runBlocking {
        val payment = debt(4, 1_000_000).copy(type = "PAYMENT")
        val input = archive(listOf(debt(2, 5_000_000), debt(3, 5_000_000), payment))
        val plan = importer().compare(input)
        assertEquals(5_000_000L, plan.current.debts - plan.current.payments)
        assertEquals(9_000_000L, plan.incoming.debts - plan.incoming.payments)
        assertEquals(2, plan.additions)
        var safety = 0
        importer { safety++ }.confirm(input, plan.sequence, owner, pharmacy)
        assertEquals(1, safety)
        assertEquals(3, dao.entryCount())
        assertEquals(9_000_000L, RecoveryTotals.of(dao.getCustomers(), dao.getEntries()).let { it.debts - it.payments })
        assertTrue(dao.cloudOutbox().isEmpty()) // restoration never impersonates an ordinary financial edit
        val remote = FakeRemote().apply { cs += customer.remote(); es += debt(2, 5_000_000).remote() }
        RecoveryCloudVerifier(dao, remote).run(owner, pharmacy)
        assertEquals(setOf(id(3), id(4)), remote.sent.toSet())
        assertTrue(dao.recoveryEntries().isEmpty())
        assertEquals(4, records().count { it.state == "VERIFIED" }) // parent + all 3 actual operations checked
    }

    @Test fun repeatedRecoveryAndLostServerResponseNeverDuplicateARecord() = runBlocking {
        val input = archive(listOf(debt(2, 5_000_000), debt(3, 4_000_000)))
        val service = importer()
        repeat(2) { service.confirm(input, service.compare(input).sequence, owner, pharmacy) }
        assertEquals(2, dao.entryCount())
        val remote = FakeRemote().apply {
            cs += customer.remote(); es += debt(2, 5_000_000).remote(); loseResponse = true
        }
        assertTrue(runCatching { RecoveryCloudVerifier(dao, remote).run(owner, pharmacy) }.isFailure)
        assertTrue(records().any { it.state == "PENDING" })
        RecoveryCloudVerifier(dao, remote).run(owner, pharmacy)
        RecoveryCloudVerifier(dao, remote).run(owner, pharmacy)
        assertEquals(listOf(id(3)), remote.sent)
        assertEquals(2, remote.es.size)
    }

    @Test fun serverDifferenceAndSoftDeletionRemainConflictsAndAreNeverOverwritten() = runBlocking {
        val input = archive(listOf(debt(2, 5_000_000), debt(3, 4_000_000)))
        importer().confirm(input, importer().compare(input).sequence, owner, pharmacy)
        val remote = FakeRemote().apply {
            cs += customer.remote()
            es += debt(2, 6_000_000).remote()
            es += debt(3, 4_000_000).remote().copy(deletedAt = Instant.ofEpochMilli(9000).toString())
        }
        RecoveryCloudVerifier(dao, remote).run(owner, pharmacy)
        assertEquals(2, records().count { it.state == "CONFLICT" })
        assertTrue(remote.sent.isEmpty())
        assertEquals(setOf(id(2), id(3)), dao.recoveryEntries().toSet())
        dao.applyCloudSnapshot(listOf(customer), listOf(debt(2, 6_000_000)))
        assertNotNull(dao.getEntryById(id(3)))
        assertTrue(runCatching { dao.deleteEntryById(id(3)) }.isFailure)
    }

    @Test fun localDifferentFinancialValuesAndDifferentIdAliasesAreNotMerged() = runBlocking {
        val altered = importer().compare(archive(listOf(debt(2, 9_000_000))))
        assertEquals(1, altered.conflicts); assertEquals(0, altered.additions)
        val alias = importer().compare(archive(listOf(debt(8, 5_000_000, 2000))))
        assertEquals(1, alias.conflicts); assertEquals(0, alias.additions)
        val twins = importer().compare(archive(listOf(debt(8, 2_000_000, 3000), debt(9, 2_000_000, 3000))))
        assertEquals(2, twins.conflicts); assertEquals(0, twins.additions)
        assertEquals(5_000_000L, dao.getEntryById(id(2))!!.amount)
    }
    @Test fun renamedCustomerWithTheSameNormalizedPhoneIsAnIdentityConflictNotANewAccount() = runBlocking {
        val renamed = customer.copy(id = id(8), name = "اسم مختلف", phone = "٠٧٠٠٠٠٠٠٠٠٠")
        val input = archive(listOf(debt(3, 9000).copy(customerId = renamed.id)), listOf(renamed))
        val plan = importer().compare(input)
        assertEquals(0, plan.additions)
        assertEquals(2, plan.conflicts)
        assertEquals(1, dao.customerCount())
    }

    @Test fun serverAliasIsQuarantinedInsteadOfCreatingAnotherOperation() = runBlocking {
        val input = archive(listOf(debt(2, 5_000_000), debt(3, 4_000_000)))
        importer().confirm(input, importer().compare(input).sequence, owner, pharmacy)
        val remote = FakeRemote().apply {
            cs += customer.remote(); es += debt(2, 5_000_000).remote()
            es += debt(8, 4_000_000, 3000).remote()
        }
        RecoveryCloudVerifier(dao, remote).run(owner, pharmacy)
        assertTrue(remote.sent.isEmpty())
        assertTrue(records().any { it.id == id(3) && it.state == "CONFLICT" })
    }

    @Test fun aNewRepaymentCannotOverdrawTheActualServerLedgerWhenAnOldDebtHasChanged() = runBlocking {
        val input = archive(listOf(debt(2, 5_000_000), debt(4, 4_000_000).copy(type = "PAYMENT")))
        importer().confirm(input, importer().compare(input).sequence, owner, pharmacy)
        val remote = FakeRemote().apply { cs += customer.remote(); es += debt(2, 1_000_000).remote() }
        RecoveryCloudVerifier(dao, remote).run(owner, pharmacy)
        assertTrue(remote.sent.isEmpty())
        assertTrue(records().any { it.id == id(4) && it.state == "CONFLICT" })
    }

    @Test fun missingNetworkLeavesDurableProtectedRowsForTheNextAttempt() = runBlocking {
        val input = archive(listOf(debt(2, 5_000_000), debt(3, 4_000_000)))
        importer().confirm(input, importer().compare(input).sequence, owner, pharmacy)
        val offline = object : RecoveryRemote by FakeRemote() {
            override suspend fun customers(): List<CloudCustomerRow> = throw IOException("fixture offline")
        }
        assertTrue(runCatching { RecoveryCloudVerifier(dao, offline).run(owner, pharmacy) }.isFailure)
        dao.applyCloudSnapshot(listOf(customer), listOf(debt(2, 5_000_000)))
        assertEquals(4_000_000L, dao.getEntryById(id(3))!!.amount)
        assertTrue(records().all { it.state == "PENDING" })
    }

    @Test fun safetyFailureAndDatabaseFailureRollBackWithoutDeletingExistingDebtOrOutbox() = runBlocking {
        val input = archive(listOf(debt(2, 5_000_000), debt(3, 4_000_000)))
        val seq = dao.latestBackupSequence()
        assertTrue(runCatching { importer { error("fixture safety failed") }.confirm(input, seq, owner, pharmacy) }.isFailure)
        assertEquals(1, dao.entryCount()); assertTrue(dao.recoveryControls().isEmpty())
        db.useWriterConnection { c -> c.usePrepared("CREATE TRIGGER reject_recovery BEFORE INSERT ON backup_control WHEN NEW.id LIKE 'restore21:%' BEGIN SELECT RAISE(ABORT, 'fixture full disk'); END") { it.step() } }
        assertTrue(runCatching { importer().confirm(input, seq, owner, pharmacy) }.isFailure)
        assertEquals(seq, dao.latestBackupSequence()); assertEquals(1, dao.entryCount())
        assertTrue(dao.recoveryControls().isEmpty()); assertTrue(dao.cloudOutbox().isEmpty())
    }

    @Test fun stalePreviewCannotApplyAndOldIdentifiersAreReportedForReview() = runBlocking {
        val input = archive(listOf(debt(2, 5_000_000), debt(3, 4_000_000)))
        val plan = importer().compare(input)
        dao.insertEntry(debt(5, 1000), "REMOTE")
        assertTrue(runCatching { importer().confirm(input, plan.sequence, owner, pharmacy) }.isFailure)
        assertNull(dao.getEntryById(id(3)))
        val old = archive(listOf(debt(6, 1000).copy(id = "old-id")))
        assertEquals(1, importer().compare(old).conflicts)
    }

    @Test fun mixedRepaymentsThatOverdrawMergedBalanceAreQuarantined() = runBlocking {
        dao.insertEntry(debt(4, 4_000_000).copy(type = "PAYMENT"), "REMOTE")
        val input = archive(listOf(debt(2, 5_000_000), debt(5, 4_000_000).copy(type = "PAYMENT")))
        val plan = importer().compare(input)
        assertEquals(1, plan.conflicts); assertTrue(plan.entries.isEmpty())
    }

    @Test fun aLegacyHeldSnapshotIsRecheckedBeforeItsGlobalHoldIsReleased() = runBlocking {
        dao.restoreLocal(listOf(customer), listOf(debt(2, 5_000_000), debt(3, 4_000_000)), emptyList())
        val input = archive(listOf(debt(2, 5_000_000)))
        importer().confirm(input, importer().compare(input).sequence, owner, pharmacy)
        assertEquals("0", dao.restoreHold())
        assertTrue(id(3) in dao.recoveryEntries())
        assertEquals(2, dao.entryCount())
    }

    @Test fun aDifferentOwnerCannotDrainTheQueueAndAChangedPharmacyCannotReceiveIt() = runBlocking {
        val input = archive(listOf(debt(2, 5_000_000), debt(3, 4_000_000)))
        importer().confirm(input, importer().compare(input).sequence, owner, pharmacy)
        val remote = FakeRemote().apply { cs += customer.remote(); es += debt(2, 5_000_000).remote() }
        RecoveryCloudVerifier(dao, remote).run(id(92), pharmacy)
        assertTrue(remote.sent.isEmpty()); assertTrue(records().all { it.state == "PENDING" })
        RecoveryCloudVerifier(dao, remote).run(owner, id(93))
        assertTrue(remote.sent.isEmpty()); assertTrue(records().all { it.state == "CONFLICT" })
    }

    @Test fun normalCurrentPendingOperationsKeepTheirRevisionAndPayload() = runBlocking {
        dao.insertEntry(debt(7, 1000))
        val queue = dao.cloudOutbox()
        val input = archive(listOf(debt(2, 5_000_000), debt(3, 4_000_000)))
        importer().confirm(input, importer().compare(input).sequence, owner, pharmacy)
        assertEquals(queue, dao.cloudOutbox())
        assertNotNull(dao.getEntryById(id(7)))
    }
    @Test fun verifiedRestoredBottleMetadataSurvivesTheServerCatchUpWithoutChangingAmounts() = runBlocking {
        val entry = debt(3, 3000).copy(bottles = 3, bottlePrice = 1000)
        val input = archive(listOf(debt(2, 5_000_000), entry))
        importer().confirm(input, importer().compare(input).sequence, owner, pharmacy)
        val remote = FakeRemote().apply { cs += customer.remote(); es += debt(2, 5_000_000).remote() }
        RecoveryCloudVerifier(dao, remote).run(owner, pharmacy)
        dao.applyCloudSnapshot(listOf(customer), listOf(debt(2, 5_000_000), entry.copy(bottles = null, bottlePrice = null)))
        assertEquals(entry, dao.getEntryById(entry.id))
    }

    @Test fun aPendingLocalDeletionIsNotResurrectedByAnOlderBackup() = runBlocking {
        dao.deleteEntryById(id(2))
        val input = archive(listOf(debt(2, 5_000_000)))
        val queue = dao.cloudOutbox()
        val plan = importer().compare(input)
        assertEquals(1, plan.conflicts); assertTrue(plan.entries.isEmpty())
        importer().confirm(input, plan.sequence, owner, pharmacy)
        assertNull(dao.getEntryById(id(2))); assertEquals(queue, dao.cloudOutbox())
    }

    @Test fun aCommittedMergeAndItsGuardsResumeAfterClosingAndReopeningTheDatabase() = runBlocking {
        val name = "recovery-fixture-only.db"
        app.deleteDatabase(name)
        fun open() = Room.databaseBuilder<PharmacyLedgerDatabase>(app, name).setDriver(AndroidSQLiteDriver()).build()
        var disk = open()
        try {
            disk.dao().insertCustomer(customer, "REMOTE")
            disk.dao().insertEntry(debt(2, 5_000_000), "REMOTE")
            val input = archive(listOf(debt(2, 5_000_000), debt(3, 4_000_000)))
            val service = RecoveryImporter(disk.dao()) {}
            service.confirm(input, service.compare(input).sequence, owner, pharmacy)
            disk.close(); disk = open()
            assertEquals(2, disk.dao().entryCount())
            assertEquals(setOf(id(2), id(3)), disk.dao().recoveryEntries().toSet())
            val remote = FakeRemote().apply { cs += customer.remote(); es += debt(2, 5_000_000).remote() }
            RecoveryCloudVerifier(disk.dao(), remote).run(owner, pharmacy)
            assertEquals(listOf(id(3)), remote.sent)
            assertEquals(owner, disk.dao().recoveryValue("restore21:refresh:" + owner))
        } finally { disk.close(); app.deleteDatabase(name) }
    }

    @Test fun theSafetyPointIsAnActualEncryptedReadableArchiveOfThePreviousLedger() = runBlocking {
        val key = BackupCrypto.newKey()
        val directory = java.io.File(app.filesDir, "local_backups_v2").apply { deleteRecursively() }
        val backup = LocalBackupEngine.forTesting(app, dao, key)
        val input = archive(listOf(debt(2, 5_000_000), debt(3, 4_000_000)))
        importer { backup.protectCurrentBeforeReconciliation() }.confirm(input, importer().compare(input).sequence, owner, pharmacy)
        val point = checkNotNull(directory.walkTopDown().firstOrNull { it.extension == "rpb" })
        val decoded = BackupArchive.restore(JSONObject(String(BackupCrypto.open(point.readBytes(), key))))
        assertEquals(1, decoded.ledger.entries.size)
        assertEquals(5_000_000L, decoded.ledger.entries.single().amount)
    }

    @Test fun theNineMillionPhoneProtectsExistingRowsAndVerifiesItsOldPendingQueueBeforeAnyOrdinaryUpsert() = runBlocking {
        val extraDebt = debt(3, 5_000_000)
        val payment = debt(4, 1_000_000).copy(type = "PAYMENT")
        dao.insertEntry(extraDebt)
        dao.insertEntry(payment)
        val before = dao.cloudOutbox()
        val input = archive(dao.getEntries())
        val plan = importer().compare(input)
        assertEquals(0, plan.additions)
        assertEquals(4, plan.existing)
        importer().confirm(input, plan.sequence, owner, pharmacy)
        assertEquals(before, dao.cloudOutbox())
        val ordinarySent = mutableListOf<String>()
        assertTrue(runCatching { DurableCloudDrain(dao) { ordinarySent += it.entityId }.flush() }.isFailure)
        assertTrue(ordinarySent.isEmpty())
        assertEquals(before, dao.cloudOutbox())
        dao.applyCloudSnapshot(listOf(customer), listOf(debt(2, 5_000_000)))
        assertEquals(9_000_000L, RecoveryTotals.of(dao.getCustomers(), dao.getEntries()).let { it.debts - it.payments })
        assertTrue(runCatching { dao.insertEntry(debt(8, 1000)) }.isFailure)
        val remote = FakeRemote().apply { cs += customer.remote(); es += debt(2, 5_000_000).remote() }
        RecoveryCloudVerifier(dao, remote).run(owner, pharmacy)
        RecoveryCloudVerifier(dao, remote).run(owner, pharmacy)
        assertEquals(listOf(extraDebt.id, payment.id), remote.sent)
        assertTrue(dao.recoveryEntries().isEmpty())
        assertTrue(dao.cloudOutbox().isEmpty()) // only the matching, server-confirmed revisions were acknowledged
        DurableCloudDrain(dao) { ordinarySent += it.entityId }.flush()
        assertTrue(ordinarySent.isEmpty())
        dao.insertEntry(debt(8, 1000)) // normal financial work resumes after verification
    }

    @Test fun aGuardedConflictingOrdinaryPayloadNeverUploadsWhileUnrelatedOperationsStillDrain() = runBlocking {
        val local = debt(2, 6_000_000)
        dao.insertEntry(local)
        dao.insertEntry(debt(7, 1000))
        val guarded = dao.cloudOutbox().single { it.entityId == local.id }
        val input = archive(listOf(local))
        importer().confirm(input, importer().compare(input).sequence, owner, pharmacy)
        val sent = mutableListOf<String>()
        assertTrue(runCatching { DurableCloudDrain(dao) { sent += it.entityId }.flush() }.isFailure)
        assertEquals(listOf(id(7)), sent)
        assertEquals(listOf(guarded), dao.cloudOutbox())
        val remote = FakeRemote().apply { cs += customer.remote(); es += debt(2, 5_000_000).remote() }
        RecoveryCloudVerifier(dao, remote).run(owner, pharmacy)
        assertTrue(records().any { it.id == local.id && it.state == "CONFLICT" })
        assertTrue(remote.sent.isEmpty())
        assertEquals(listOf(guarded), dao.cloudOutbox())
        assertTrue(runCatching { DurableCloudDrain(dao) { sent += it.entityId }.flush() }.isFailure)
        assertEquals(listOf(id(7)), sent)
        assertTrue(runCatching { dao.insertEntry(debt(8, 1000)) }.isFailure)
        assertTrue(runCatching { dao.deleteEntryById(id(7)) }.isFailure)
        dao.applyCloudSnapshot(listOf(customer), listOf(debt(2, 5_000_000)))
        assertEquals(local, dao.getEntryById(local.id))
    }

    @Test fun aServerConfirmationCannotAcknowledgeAnOrdinaryPayloadThatDiffersFromTheCheckedRecord() = runBlocking {
        val input = archive(listOf(debt(2, 5_000_000)))
        importer().confirm(input, importer().compare(input).sequence, owner, pharmacy)
        // Simulate a different durable revision racing with a stale verification response.
        val newer = CloudOutboxEntity.from(BackupChangeEntity.entry(debt(2, 6_000_000), "LOCAL"))
        dao.saveCloudMutation(newer)
        val remote = FakeRemote().apply { cs += customer.remote(); es += debt(2, 5_000_000).remote() }
        RecoveryCloudVerifier(dao, remote).run(owner, pharmacy)
        assertEquals(listOf(newer), dao.cloudOutbox())
        assertTrue(remote.sent.isEmpty())
        assertTrue(dao.recoveryEntries().isEmpty())
    }

    private suspend fun records() = dao.recoveryControls().filter { it.id.startsWith(RecoveryKeys.ITEM) }.map { RecoveryRecord.parse(it.value) }
    private fun CustomerEntity.remote() = CloudCustomerRow(id, pharmacy, name, phone, area, address, openingDebt,
        notes, createdByName = createdByName, createdAt = Instant.ofEpochMilli(createdAt).toString(), updatedAt = Instant.ofEpochMilli(createdAt).toString())
    private fun LedgerEntryEntity.remote() = CloudTransactionRow(id, pharmacy, customerId, id, type, amount.toDouble(),
        details, occurredAt = Instant.ofEpochMilli(createdAt).toString(), createdAt = Instant.ofEpochMilli(createdAt).toString(), updatedAt = Instant.ofEpochMilli(createdAt).toString())
    private inner class FakeRemote : RecoveryRemote {
        val cs = mutableListOf<CloudCustomerRow>(); val es = mutableListOf<CloudTransactionRow>()
        val sent = mutableListOf<String>(); var loseResponse = false
        override suspend fun customers() = cs.toList()
        override suspend fun entries() = es.toList()
        override suspend fun customer(id: String) = cs.singleOrNull { it.id == id }
        override suspend fun entry(id: String) = es.singleOrNull { it.id == id }
        override suspend fun insert(customer: CustomerEntity) { check(cs.none { it.id == customer.id }); cs += customer.remote(); sent += customer.id }
        override suspend fun insert(entry: LedgerEntryEntity) {
            check(es.none { it.id == entry.id }); es += entry.remote(); sent += entry.id
            if (loseResponse) { loseResponse = false; throw IOException("fixture response lost after commit") }
        }
    }
    companion object { private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0') }
}

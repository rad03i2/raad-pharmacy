package com.radwan.raadpharmacy.cloud

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudLedgerPullerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("test_pull_cursors", Context.MODE_PRIVATE)
    private lateinit var db: PharmacyLedgerDatabase
    private lateinit var dao: PharmacyLedgerDao
    private val customer = CloudCustomerRow("customer", "pharmacy", "زبون",
        createdAt = "2026-10-10T10:00:00Z", updatedAt = "2026-10-15T10:00:00Z")
    private val server = mutableListOf<CloudTransactionRow>()
    private val requested = mutableListOf<Pair<String?, String?>>()
    private var customerSince: String? = null
    private var failTransactions = false

    @Before fun prepare() {
        prefs.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder<PharmacyLedgerDatabase>(context)
            .setDriver(AndroidSQLiteDriver()).build()
        dao = db.dao()
    }
    @After fun close() { db.close() }

    private fun debt(id: String, updated: String, amount: Double = 5000.0) = CloudTransactionRow(
        id, "pharmacy", customer.id, id, "DEBT", amount,
        occurredAt = updated, createdAt = updated, updatedAt = updated)

    private fun receiver() = CloudLedgerPuller(prefs,
        customers = { since ->
            customerSince = since
            listOf(customer).filter { since == null || Instant.parse(it.updatedAt) >= Instant.parse(since) }
        }, transactions = { since ->
            requested += customerSince to since
            if (failTransactions) throw IOException("network unavailable")
            server.filter { since == null || Instant.parse(it.updatedAt) >= Instant.parse(since) }
        }, apply = { customers, entries, full ->
            val cs = customers.map { CustomerEntity(it.id, it.name, null, "", "", 0, "", 1) }
            val es = entries.map { LedgerEntryEntity(it.id, it.customerId, it.type, it.amount.toLong(),
                null, null, "", Instant.parse(it.occurredAt).toEpochMilli()) }
            if (full) dao.applyCloudSnapshot(cs, es)
            else {
                cs.forEach { dao.applyCloudCustomer(it.id, it) }
                es.forEach { dao.applyCloudEntry(it.id, it) }
            }
        })

    @Test fun upgradingAReceiverWithAFuturePhoneCheckpointRecoversOldClientDebtsAndKeepsReceiving() = runBlocking {
        prefs.edit().putLong("last_remote_pull_at", Instant.parse("2099-01-01T00:00:00Z").toEpochMilli()).commit()
        server += debt("from-3.3.22", "2026-10-12T10:00:00Z")
        receiver().pull()
        assertEquals(null to null, requested.first())
        assertEquals(5000L, dao.getEntryById("from-3.3.22")!!.amount)
        // A customer edited today must not skip an older financial row received later.
        server += debt("from-another-phone", "2026-10-12T10:01:00Z", 7000.0)
        receiver().pull()
        assertEquals("2026-10-15T09:59:55Z", requested.last().first)
        assertEquals("2026-10-12T09:59:55Z", requested.last().second)
        assertEquals(2, dao.entryCount())
        assertTrue(dao.cloudOutbox().isEmpty())
    }

    @Test fun aFailedUploadStillReceivesOtherPhonesDebtsAndRemainsRetryable() = runBlocking {
        server += debt("remote", "2026-10-12T10:00:00Z")
        val uploadError = IOException("local upload rejected")
        val result = runCatching { syncBothDirections({ throw uploadError }, { receiver().pull() }) }
        assertSame(uploadError, result.exceptionOrNull())
        assertEquals(5000L, dao.getEntryById("remote")!!.amount)
    }

    @Test fun recoverySnapshotPreservesUnsentLocalOperationsWhileImportingRemoteDebts() = runBlocking {
        dao.insertCustomer(CustomerEntity(customer.id, customer.name, null, "", "", 0, "", 1))
        dao.insertEntry(LedgerEntryEntity("unsent", customer.id, "DEBT", 3000, null, null, "", 2))
        val pending = dao.cloudOutbox()
        server += debt("remote", "2026-10-12T10:00:00Z")
        receiver().pull()
        assertEquals(setOf("unsent", "remote"), dao.getEntries().map { it.id }.toSet())
        assertEquals(pending, dao.cloudOutbox())
        assertEquals(8000L, dao.getEntries().sumOf { it.amount })
    }

    @Test fun failedReceptionDoesNotAdvanceEitherCursorOrLoseTheMissingDebt() = runBlocking {
        server += debt("first", "2026-10-12T10:00:00Z")
        receiver().pull()
        val before = prefs.all.toMap()
        server += debt("next", "2026-10-12T10:01:00Z")
        failTransactions = true
        assertTrue(runCatching { receiver().pull() }.isFailure)
        assertEquals(before, prefs.all)
        assertNull(dao.getEntryById("next"))
        failTransactions = false
        receiver().pull()
        assertNotNull(dao.getEntryById("next"))
    }

    @Test fun cancellingTheUploadDoesNotStartMoreNetworkWork() = runBlocking {
        var received = false
        val result = runCatching { syncBothDirections({ throw CancellationException() }, { received = true }) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(received)
    }
}

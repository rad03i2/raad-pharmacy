package com.radwan.raadpharmacy.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReleaseCandidateRoomTest {
    private lateinit var db: PharmacyLedgerDatabase
    private lateinit var dao: PharmacyLedgerDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder<PharmacyLedgerDatabase>(context)
            .setDriver(AndroidSQLiteDriver())
            .build()
        dao = db.dao()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun failedReplaceAll_rollsBackAndKeepsPreviousLedger() = runTest {
        val original = CustomerEntity(
            id = "original",
            name = "البيانات الأصلية",
            phone = null,
            area = "",
            address = "",
            openingDebt = 2_000L,
            notes = "",
            createdAt = 1_000L
        )
        dao.insertCustomer(original)

        val incoming = CustomerEntity(
            id = "incoming",
            name = "نسخة جديدة",
            phone = null,
            area = "",
            address = "",
            openingDebt = 0L,
            notes = "",
            createdAt = 2_000L
        )
        val orphan = LedgerEntryEntity(
            id = "orphan",
            customerId = "missing-customer",
            type = EntryType.DEBT.name,
            amount = 1_000L,
            bottles = null,
            bottlePrice = null,
            details = "",
            createdAt = 3_000L
        )

        assertThrows(Exception::class.java) {
            runBlocking {
                dao.replaceAll(
                    customers = listOf(incoming),
                    entries = listOf(orphan)
                )
            }
        }

        assertEquals(1, dao.customerCount())
        assertNotNull(dao.getCustomerById(original.id))
        assertEquals(0, dao.entryCount())
    }

    @Test
    fun summaryQueriesStayCorrectWithHundredsOfAccounts() = runTest {
        val customers = (1..250).map { index ->
            CustomerEntity(
                id = "c-" + index,
                name = "زبون " + index,
                phone = null,
                area = "منطقة " + ((index % 5) + 1),
                address = "",
                openingDebt = 1_000L,
                notes = "",
                createdAt = 1_000L + index
            )
        }
        val entries = customers.flatMapIndexed { index, customer ->
            listOf(
                LedgerEntryEntity(
                    id = "d-" + index,
                    customerId = customer.id,
                    type = EntryType.DEBT.name,
                    amount = 10_000L,
                    bottles = 1,
                    bottlePrice = 10_000L,
                    details = "",
                    createdAt = 10_000L + index * 2L
                ),
                LedgerEntryEntity(
                    id = "p-" + index,
                    customerId = customer.id,
                    type = EntryType.PAYMENT.name,
                    amount = 4_000L,
                    bottles = null,
                    bottlePrice = null,
                    details = "",
                    createdAt = 10_001L + index * 2L
                )
            )
        }

        dao.insertCustomers(customers)
        dao.insertEntries(entries)

        val summary = dao.currentDebtSummary()
        assertEquals(250, summary.totalCustomers)
        assertEquals(250, summary.openAccounts)
        assertEquals(1_750_000L, summary.totalDebt)

        val metrics = dao.reportPeriodMetrics(0L, Long.MAX_VALUE)
        assertEquals(2_500_000L, metrics.debts)
        assertEquals(1_000_000L, metrics.collections)
        assertEquals(250L, metrics.bottles)
    }
}

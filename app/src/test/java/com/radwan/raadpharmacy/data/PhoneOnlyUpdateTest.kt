package com.radwan.raadpharmacy.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PhoneOnlyUpdateTest {
    @Test fun phoneSaveMergesFreshCustomerFieldsAndRetainsLedgerAndDurableOutbox() = runTest {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder<PharmacyLedgerDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
        try {
            val dao=db.dao()
            val customer=Customer("c","fixture",openingDebt=100,notes="edited while picker open",createdAt=1000)
            dao.insertCustomer(customer.toEntity())
            val debt=LedgerEntry("d","c",EntryType.DEBT,700,createdAt=2000)
            dao.insertEntry(debt.toEntity())
            val updated=dao.addMissingPhone("c","07700000000").toModel()
            assertEquals(customer.copy(phone="07700000000"),updated)
            assertEquals(debt,dao.getEntryById("d")!!.toModel())
            assertTrue(dao.cloudOutbox().any { it.kind=="CUSTOMER" && it.entityId=="c" && it.payload.contains("07700000000") })
            var rejected=false
            try { dao.addMissingPhone("c","07800000000") } catch(_:IllegalStateException){rejected=true}
            assertTrue(rejected); assertEquals("07700000000",dao.getCustomerById("c")!!.phone)
        } finally { db.close() }
    }
    @Test fun shortTermsSurviveRoomAndPortableBackupWithoutFinancialChanges() = runTest {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder<PharmacyLedgerDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
        try {
            val customer=Customer("c","fixture",createdAt=1000)
            val entries=RepaymentTerms.choices.mapIndexed { i,term -> LedgerEntry("d$i","c",EntryType.DEBT,100,details="دواء",createdAt=2000+i.toLong(),repaymentDays=term) }
            db.dao().insertCustomer(customer.toEntity()); entries.forEach { db.dao().insertEntry(it.toEntity()) }
            assertEquals(entries.toSet(),db.dao().getEntries().map { it.toModel() }.toSet())
            assertEquals(entries,BackupJson.parse(BackupJson.encode(listOf(customer),entries,"3.3.22.23")).entries)
        } finally { db.close() }
    }
}

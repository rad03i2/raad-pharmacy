package com.radwan.raadpharmacy.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.cloud.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class BulkAreaUpdateTest {
    private fun database()=Room.inMemoryDatabaseBuilder<PharmacyLedgerDatabase>(ApplicationProvider.getApplicationContext<Context>()).setDriver(AndroidSQLiteDriver()).build()
    @Test fun freshRowsOnlyChangeAreaAndRetryDoesNotDuplicateOrAlterLedger()=runTest {
        val db=database();try {
            val dao=db.dao()
            val c=Customer("c","اسم أحدث",phone="07700000000",area="قديم",openingDebt=9_000_000,notes="ملاحظات أحدث",createdAt=1234)
            dao.insertCustomer(c.toEntity());dao.cloudOutbox().forEach {dao.acknowledgeCloudMutation(it.key,it.revision)}
            val debt=LedgerEntry("d","c",EntryType.DEBT,80_000,createdAt=2000)
            val payment=LedgerEntry("p","c",EntryType.PAYMENT,50_000,createdAt=3000)
            dao.insertEntry(debt.toEntity());dao.insertEntry(payment.toEntity())
            assertEquals(listOf(c.copy(area="جديد")),dao.assignCustomerAreas(mapOf("c" to "قديم","missing" to "قديم"),"جديد").map {it.toModel()})
            val pending=dao.cloudMutation("CUSTOMER:c")!!;assertTrue(pending.isAreaOnly())
            assertEquals(c.copy(area="جديد"),BackupJson.parse(pending.payload).customers.single())
            assertEquals(setOf(debt,payment),dao.getEntries().map {it.toModel()}.toSet())
            assertTrue(dao.assignCustomerAreas(mapOf("c" to "قديم"),"جديد").isEmpty())
            assertEquals(pending,dao.cloudMutation("CUSTOMER:c"))
            // A stale acknowledgement cannot remove a newer metadata intent.
            dao.assignCustomerAreas(mapOf("c" to "جديد"),"الثالثة")
            dao.acknowledgeCloudMutation(pending.key,pending.revision)
            assertEquals("الثالثة",BackupJson.parse(dao.cloudMutation(pending.key)!!.payload).customers.single().area)
        } finally {db.close()}
    }
    @Test fun pendingFullCustomerIntentAndConcurrentLocalAreaEditsArePreserved()=runTest {
        val db=database();try {
            val dao=db.dao();val c=Customer("c","اسم",area="الأولى",openingDebt=5_000_000,createdAt=100)
            dao.insertCustomer(c.toEntity()) // New customer still needs a full insert.
            dao.assignCustomerAreas(mapOf("c" to "الأولى"),"الثانية")
            assertFalse(dao.cloudMutation("CUSTOMER:c")!!.isAreaOnly())
            assertEquals(c.copy(area="الثانية"),dao.getCustomerById("c")!!.toModel())
            assertTrue(dao.assignCustomerAreas(mapOf("c" to "الأولى"),"منطقة قديمة الاختيار").isEmpty())
            assertEquals("الثانية",dao.getCustomerById("c")!!.area)
        } finally {db.close()}
    }
    @Test fun recoveryHoldRollsBackTheWholeBulkOperation()=runTest {
        val db=database();try {
            val dao=db.dao();val c=Customer("c","اختبار",area="أ",createdAt=100)
            dao.insertCustomer(c.toEntity());dao.insertCustomer(c.copy(id="other").toEntity())
            val before=dao.getCustomers();val outbox=dao.cloudOutbox()
            dao.saveBackupControl(com.radwan.raadpharmacy.backup.BackupControlEntity(com.radwan.raadpharmacy.backup.RecoveryKeys.guard("CUSTOMER","other"),"held"))
            var rejected=false
            try {dao.assignCustomerAreas(linkedMapOf("c" to "أ","other" to "أ"),"ب")} catch(_:IllegalStateException) {rejected=true}
            assertTrue(rejected);assertEquals(before,dao.getCustomers());assertEquals(outbox,dao.cloudOutbox())
        } finally {db.close()}
    }
}

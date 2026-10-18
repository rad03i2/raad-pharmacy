package com.radwan.raadpharmacy.suppliers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.data.MonthlyReports
import com.radwan.raadpharmacy.shopping.*
import java.io.File
import java.time.YearMonth
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35])
class SupplierLedgerTest {
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private val owner=ShoppingIdentity(UUID.randomUUID().toString(),UUID.randomUUID().toString())
    private lateinit var file: File;private lateinit var local: SupplierLocalStore
    private fun row(day: String,amount: Long,kind: String="DEBT")=SupplierEntry(UUID.randomUUID().toString(),"مذخر تجريبي",kind,amount,day)
    @Before fun open() {file=File(context.cacheDir,"supplier-${UUID.randomUUID()}.db");local=SupplierLocalStore(context,file.path)}
    @After fun close() {local.close();file.delete()}
    @Test fun actualMonthlyMovementDiffersFromClosingPayableAndDoesNotIncludeFutureRows() {
        val rows=listOf(row("2026-12-31",5_000_000),row("2027-01-10",2_000_000),row("2027-01-20",1_000_000,"PAYMENT"),row("2027-02-01",9_000_000))
        val result=SupplierReports.totals(rows,MonthlyReports.month(YearMonth.of(2027,1)))
        assertEquals(2_000_000L,result.debts);assertEquals(1_000_000L,result.payments);assertEquals(6_000_000L,result.closing)
    }
    @Test fun lostReplyAndReopenRetainExactValuesAndIdUntilExactReceipt() {
        val row=row("2026-10-18",2000)
        local.add(owner,row);local.close();local=SupplierLocalStore(context,file.path)
        local.add(owner,row);assertEquals(1,local.list(owner).size);assertTrue(local.list(owner).single().pending)
        assertThrows(IllegalStateException::class.java) {local.acknowledge(owner,row,ShoppingReceipt("wrong",owner.pharmacy,row.id,true))}
        local.acknowledge(owner,row,ShoppingReceipt(owner.owner,owner.pharmacy,row.id,true))
        assertFalse(local.list(owner).single().pending)
        assertThrows(IllegalStateException::class.java) {local.add(owner,row.copy(amount=3000))}
        assertEquals(2000L,local.list(owner).single().amount)
    }
    @Test fun separateAccountsAndTenantsCannotUploadEachOthersPendingEntries() {
        local.add(owner,row("2026-10-18",100))
        assertTrue(local.list(owner.copy(owner=UUID.randomUUID().toString())).isEmpty())
        assertTrue(local.list(owner.copy(pharmacy=UUID.randomUUID().toString())).isEmpty())
    }
    @Test fun invalidFinancialEntriesAreRejectedInsteadOfCoercingAmountOrDate() {
        val row=row("2026-10-18",100)
        listOf(row.copy(amount=0),row.copy(amount=-1),row.copy(kind="OTHER"),row.copy(day="2027-02-30"),row.copy(supplier="")).forEach {
            assertThrows(Exception::class.java) {local.add(owner,it)}
        };assertTrue(local.list(owner).isEmpty())
    }
}

package com.radwan.raadpharmacy.followup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.data.*
import java.time.LocalDate
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35])
class ManualFollowupTest {
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private val owner=FollowupIdentity("raad","pharmacy")
    private val other=FollowupIdentity("ahmad","pharmacy")
    private val date=LocalDate.of(2026,10,20)
    private val stamp=date.minusDays(2).atStartOfDay(DebtFollowupEngine.IRAQ_ZONE).toInstant().toEpochMilli()
    private val customer=Customer("customer","زبون تجريبي",createdAt=stamp)
    private val debt=LedgerEntry("debt",customer.id,EntryType.DEBT,40_000L,createdAt=stamp)
    private lateinit var local: ManualFollowupLocal
    private lateinit var name: String
    @Before fun open() {name="followup-test-${UUID.randomUUID()}.db";local=ManualFollowupLocal(context,name)}
    @After fun close() {local.close();context.deleteDatabase(name)}
    private fun account(entries: List<LedgerEntry> = listOf(debt),person: Customer=customer)=DebtFollowupEngine.calculate(person,entries,date)
    @Test fun manualInclusionDoesNotInventThirtyDaysOrChangeMoneyOrFinancialRows() {
        val original=account()
        val marked=applyManualFollowups(listOf(original),listOf(ManualFollowupCase(customer.id,original.unpaidDebtKeys))).single()
        assertTrue(marked.manuallyFollowed);assertTrue(inFollowup(marked));assertEquals(2L,marked.ageDays)
        assertEquals(original,marked.copy(manuallyFollowed=false));assertEquals(0L,marked.overdueAmount)
    }
    @Test fun partialPaymentRetainsManualCaseButSettlementDoesNotMarkFutureFreshDebt() {
        val row=ManualFollowupCase(customer.id,account().unpaidDebtKeys)
        val payment=LedgerEntry("payment",customer.id,EntryType.PAYMENT,10_000L,createdAt=stamp+1000)
        assertTrue(applyManualFollowups(listOf(account(listOf(debt,payment))),listOf(row)).single().manuallyFollowed)
        val settled=payment.copy(amount=40_000L)
        assertFalse(applyManualFollowups(listOf(account(listOf(debt,settled))),listOf(row)).single().manuallyFollowed)
        val fresh=debt.copy(id="fresh",createdAt=stamp+2000)
        assertFalse(applyManualFollowups(listOf(account(listOf(debt,settled,fresh))),listOf(row)).single().manuallyFollowed)
    }
    @Test fun openingDebtIsIncludedWithoutChangingItsDateAndEditedOpeningDoesNotReuseMark() {
        val person=customer.copy(openingDebt=70_000L)
        val old=account(emptyList(),person)
        assertEquals(setOf("opening:$stamp:70000"),old.unpaidDebtKeys)
        assertTrue(applyManualFollowups(listOf(old),listOf(ManualFollowupCase(person.id,old.unpaidDebtKeys))).single().manuallyFollowed)
        assertFalse(applyManualFollowups(listOf(account(emptyList(),person.copy(openingDebt=80_000L))),listOf(ManualFollowupCase(person.id,old.unpaidDebtKeys))).single().manuallyFollowed)
    }
    @Test fun queuedBulkSurvivesProcessRecreationAndIsScopedToExactUserAndPharmacy() {
        val cases=(1..100).map {ManualFollowupCase("customer-$it",setOf("entry:debt-$it"))}
        local.add(owner,cases);local.close();local=ManualFollowupLocal(context,name)
        assertEquals(100,local.pending(owner).size);assertTrue(local.list(other).isEmpty())
        assertTrue(local.list(owner.copy(pharmacy="another-pharmacy")).isEmpty())
        local.add(owner,listOf(cases.first()));assertEquals(100,local.list(owner).size)
    }
    @Test fun wrongCloudReceiptAndStaleRevisionNeverAcknowledgeNewerPendingAddition() {
        val first=ManualFollowupCase("customer",setOf("entry:first"))
        local.add(owner,listOf(first));val op=local.pending(owner).single()
        try {local.acknowledge(owner,op,ManualFollowupReceipt(other.owner,owner.pharmacy,first.customer,first.debts));fail("wrong owner")}
        catch(_: IllegalStateException) {assertEquals(1,local.pending(owner).size)}
        local.add(owner,listOf(first.copy(debts=setOf("entry:second"))))
        local.acknowledge(owner,op,ManualFollowupReceipt(owner.owner,owner.pharmacy,first.customer,first.debts))
        assertEquals(setOf("entry:first","entry:second"),local.pending(owner).single().case.debts)
        local.merge(owner,ManualFollowupSnapshot(owner.owner,owner.pharmacy,listOf(first)))
        assertEquals(1,local.pending(owner).size)
        val newest=local.pending(owner).single()
        local.acknowledge(owner,newest,ManualFollowupReceipt(owner.owner,owner.pharmacy,first.customer,newest.case.debts))
        assertTrue(local.pending(owner).isEmpty());assertEquals(2,local.list(owner).single().debts.size)
    }
}

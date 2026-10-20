package com.radwan.raadpharmacy.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.radwan.raadpharmacy.data.*
import com.radwan.raadpharmacy.followup.*
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BulkManualFollowupUiTest {
    @get:Rule val rule=createComposeRule()
    @Test fun bulkSelectionUsesCustomerIdsAndExcludesPaidOrAlreadyFollowedAccounts() {
        val stamp=System.currentTimeMillis()-86_400_000L
        val customers=listOf(Customer("a","أحمد",createdAt=stamp),Customer("b","أحمد",createdAt=stamp),Customer("paid","مسدد",createdAt=stamp),Customer("old","قديم",createdAt=stamp))
        val entries=customers.filter {it.id!="paid"}.map {LedgerEntry("debt-${it.id}",it.id,EntryType.DEBT,1000L,createdAt=stamp)}
        val records=DebtFollowupEngine.build(customers,entries).map {if(it.customer.id=="old") it.copy(manuallyFollowed=true) else it}
        var accounts by mutableStateOf(records)
        var actual=emptySet<String>();var exited=false
        rule.setContent {PharmacyLedgerTheme {BulkManualFollowupContent(accounts,FollowupIdentity("raad","pharmacy"),false,{exited=true}) {_,ids->
            actual=ids;accounts=accounts.map {if(it.customer.id in ids) it.copy(manuallyFollowed=true) else it};"تمت الإضافة"
        }}}
        rule.onNodeWithText("مسدد").assertDoesNotExist()
        rule.onNodeWithText("تحديد النتائج (2)").performClick()
        rule.onNodeWithText("إضافة 2 زبون إلى المتابعة").performClick()
        rule.onNodeWithText("تأكيد الإضافة").performClick()
        rule.onNodeWithText("تمت الإضافة").assertExists()
        rule.runOnIdle {assertEquals(setOf("a","b"),actual);assertFalse(exited)}
        rule.onNodeWithText("حسناً").performClick()
        rule.onNodeWithText("لا توجد حسابات مطابقة خارج قائمة المتابعة.").assertExists()
    }
}

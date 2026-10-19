package com.radwan.raadpharmacy.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.radwan.raadpharmacy.data.*
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
class BulkAreasScreenTest {
    @get:Rule val rule=createComposeRule()
    @Test fun assigningMultipleCustomersUsesIdsAndReviewsMovesWithoutLeavingSettings() {
        var customers by mutableStateOf(listOf(Customer("a","أحمد",area="الكرادة",createdAt=1),Customer("b","أحمد",area="المنصور",createdAt=2),Customer("c","فؤاد",createdAt=3)))
        var actual=emptyMap<String,String>();var target="";var exited=false
        rule.setContent {PharmacyLedgerTheme {
            BulkCustomerAreasContent(customers,listOf("زيونة"),{exited=true},{},{ids,area ->
                actual=ids;target=area;customers=customers.map {if(it.id in ids) it.copy(area=area) else it};MutationResult(true,"تم الحفظ")
            })
        }}
        rule.onNodeWithText("زيونة").performClick()
        rule.onNodeWithText("إضافة زبائن لهذه المنطقة").performClick()
        rule.onNodeWithText("تحديد النتائج (3)").performClick()
        rule.onNodeWithText("إضافة 3 زبون إلى المنطقة").performClick()
        rule.onNodeWithText("سيُنقل 2 زبون من مناطقهم السابقة إلى هذه المنطقة.").assertExists()
        rule.onNodeWithText("تأكيد إضافة الزبائن").performClick()
        rule.waitUntil {actual.size==3}
        assertEquals(mapOf("a" to "الكرادة","b" to "المنصور","c" to ""),actual)
        assertEquals("زيونة",target);assertFalse(exited)
        rule.onNodeWithText("حسناً").performClick()
        rule.onNodeWithText("3 زبون في المنطقة").assertExists()
    }
    @Test fun anEmptyAreaCanBeCreatedAndOpenedBeforeAddingCustomers() {
        var created=""
        rule.setContent {PharmacyLedgerTheme {BulkCustomerAreasContent(emptyList(),emptyList(),{},{created=it},{_,_->MutationResult(true,"")})}}
        rule.onNodeWithText("إنشاء منطقة جديدة").performClick()
        rule.onNodeWithText("اسم المنطقة").performTextInput("  بغداد   الجديدة  ")
        rule.onNodeWithText("حفظ المنطقة").performClick()
        rule.waitUntil {created.isNotEmpty()};assertEquals("بغداد الجديدة",created)
        rule.onNodeWithText("0 زبون في المنطقة").assertExists()
        rule.onNodeWithText("إضافة زبائن لهذه المنطقة").assertIsEnabled()
    }
}

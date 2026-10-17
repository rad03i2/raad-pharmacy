package com.radwan.raadpharmacy.ui.screens

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.MutationResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MissingPhoneListTest {
    @get:Rule val rule=createComposeRule()
    @Test fun selectingPhoneInMiddleRemovesOnlyTargetAndKeepsListAtAdjacentCustomer() {
        var customers by mutableStateOf((0..199).map { Customer("c$it","زبون %03d".format(it),openingDebt=it.toLong()) })
        var navigations=0;var saves=0
        rule.setContent { MaterialTheme { MissingPhoneCustomersList(customers,false,{it.openingDebt},
            { id,phone -> saves++;customers=customers.map { if(it.id==id)it.copy(phone=phone)else it };MutationResult(true,"") },
            {},{navigations++}, contactPicker={ selected,dismissed ->
                // Exercise the list/picker callback boundary without relying on Android's runtime-permission UI.
                AlertDialog(onDismissRequest=dismissed,title={Text("جهة اتصال للتجربة")},
                    confirmButton={TextButton(onClick={selected("07701234567")}){Text("اختيار الرقم")}})
            }) } }
        rule.onNodeWithTag("missing-phone-list").performScrollToIndex(102)
        rule.waitForIdle()
        rule.onNode(hasText("إضافة رقم هاتف") and hasAnyAncestor(hasTestTag("missing-phone:c100"))).performClick()
        rule.onNodeWithText("جهة اتصال للتجربة").assertIsDisplayed()
        rule.onNodeWithText("اختيار الرقم").performClick()
        rule.waitForIdle()
        assertEquals(0,navigations);assertEquals(1,saves)
        assertEquals("07701234567",customers.single { it.id=="c100" }.phone)
        assertEquals(199,customers.count { it.phone.isNullOrBlank() })
        rule.onNodeWithTag("missing-phone:c100").assertDoesNotExist()
        rule.onNodeWithTag("missing-phone:c101").assertIsDisplayed()
        rule.onNodeWithTag("missing-phone:c0").assertDoesNotExist()
        rule.onNodeWithText("زبائن ليس لديهم رقم هاتف").assertIsDisplayed()
    }
}

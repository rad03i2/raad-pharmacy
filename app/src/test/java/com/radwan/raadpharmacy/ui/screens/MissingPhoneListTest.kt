package com.radwan.raadpharmacy.ui.screens

import android.Manifest
import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.MutationResult
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MissingPhoneListTest {
    @get:Rule val rule=createComposeRule()
    @Before fun contacts() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        val provider=object:ContentProvider() {
            override fun onCreate()=true
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,order:String?):Cursor =
                MatrixCursor(arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER))
                    .apply { addRow(arrayOf("جهة اتصال للتجربة","07701234567")) }
            override fun getType(uri:Uri):String?=null
            override fun insert(uri:Uri,values:ContentValues?):Uri?=null
            override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=0
            override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=0
        }
        provider.attachInfo(app,android.content.pm.ProviderInfo().apply { authority=ContactsContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY,provider)
    }
    @Test fun selectingPhoneInMiddleRemovesOnlyTargetAndKeepsListAtAdjacentCustomer() {
        var customers by mutableStateOf((0..199).map { Customer("c$it","زبون %03d".format(it),openingDebt=it.toLong()) })
        var navigations=0;var saves=0
        rule.setContent { MaterialTheme { MissingPhoneCustomersList(customers,false,{it.openingDebt},
            { id,phone -> saves++;customers=customers.map { if(it.id==id)it.copy(phone=phone)else it };MutationResult(true,"") },
            {},{navigations++}) } }
        rule.onNodeWithTag("missing-phone-list").performScrollToIndex(102)
        rule.waitForIdle()
        rule.onNode(hasText("إضافة رقم هاتف") and hasAnyAncestor(hasTestTag("missing-phone:c100"))).performClick()
        // Let the real IO provider query finish while the indeterminate progress animation is paused.
        rule.mainClock.autoAdvance=false
        repeat(50) { rule.mainClock.advanceTimeByFrame(); Thread.sleep(10) }
        rule.mainClock.autoAdvance=true
        rule.waitUntil(10_000) { rule.onAllNodesWithText("جهة اتصال للتجربة").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("جهة اتصال للتجربة").performClick()
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

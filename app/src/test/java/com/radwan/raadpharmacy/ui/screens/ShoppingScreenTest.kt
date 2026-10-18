package com.radwan.raadpharmacy.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.shopping.*
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w420dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShoppingScreenTest {
    @get:Rule val rule=createComposeRule()
    private val identity=ShoppingIdentity("fixture","00000000-0000-4000-8000-000000000001")
    private val rows=(1..70).map {ShoppingItem(UUID.randomUUID().toString(),"علاج رقم $it", "علبتان • تركيز 500 ملغ",false,1_792_100_000_000L,"رعد")}
    @Test fun purchasingMidListKeepsNeighbourAndSearchState() {
        var state by mutableStateOf(ShoppingUiState(identity,rows,lastSyncedAt=100))
        rule.setContent {PharmacyLedgerTheme(darkTheme=false) {CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.width(380.dp).testTag("shopping-preview")) { ShoppingListContent(state,{},{},{id->state=state.copy(items=state.items.filterNot {it.id==id})},{}) }
        }}}
        rule.onNodeWithTag("shopping-list").performScrollToIndex(35)
        rule.onNodeWithContentDescription("تم شراء علاج رقم 36").performClick()
        rule.waitForIdle();rule.onNodeWithText("علاج رقم 37").assertIsDisplayed()
        rule.onNodeWithText("علاج رقم 1").assertDoesNotExist()
        save("shopping-light-list.png")
        rule.onNodeWithText("ابحث عن علاج أو ملاحظة").performTextInput("رقم 60")
        rule.onNodeWithText("علاج رقم 60").assertIsDisplayed()
        rule.onNodeWithText("نتائج البحث • 1").assertExists()
    }
    @Test fun darkNarrowOfflineListExplainsLocalPhotosAndPendingDeletes() {
        var added=false
        val photo=rows.first().copy(title="",hasPhoto=true,pending=true)
        rule.setContent {PharmacyLedgerTheme(darkTheme=true,textScale=1.15f) {CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.width(320.dp).testTag("shopping-preview")) { ShoppingListContent(ShoppingUiState(identity,listOf(photo),pending=2,error="القائمة محفوظة على الهاتف"),
                {},{added=true},{},{}) }
        }}}
        rule.onNodeWithText("صورة محلية").assertIsDisplayed()
        rule.onNodeWithText("محفوظ على الهاتف • 2 بانتظار المزامنة").assertExists()
        rule.onNodeWithText("إضافة علاج").performClick();assertTrue(added)
        save("shopping-dark-local-photo.png")
        rule.onNodeWithContentDescription("شرح قائمة الشراء").performClick()
        rule.onNodeWithText("قائمة تستمر معك").assertIsDisplayed()
    }
    private fun save(name:String) {val bitmap=rule.onNodeWithTag("shopping-preview").captureToImage().asAndroidBitmap()
        File("build/reports/shopping-preview").apply {mkdirs()}.resolve(name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
}

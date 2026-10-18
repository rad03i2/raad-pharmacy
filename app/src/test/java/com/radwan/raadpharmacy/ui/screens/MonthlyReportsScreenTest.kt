package com.radwan.raadpharmacy.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import com.radwan.raadpharmacy.data.*
import com.radwan.raadpharmacy.suppliers.SupplierEntry
import java.util.UUID
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import com.radwan.raadpharmacy.util.formatMoney
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w420dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonthlyReportsScreenTest {
    @get:Rule val rule=createComposeRule()
    @Test fun changingMonthDisplaysOnlyItsActualDebtAndReceiptTotals() {
        val january=MonthlyReports.month(YearMonth.of(2027,1)).start
        val december=MonthlyReports.month(YearMonth.of(2026,12)).start
        val entries=listOf(LedgerEntry("jan","c",EntryType.DEBT,9000,createdAt=january),
            LedgerEntry("dec","c",EntryType.DEBT,7000,createdAt=december),
            LedgerEntry("pay","c",EntryType.PAYMENT,3000,createdAt=december+1000))
        rule.setContent { PharmacyLedgerTheme { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.testTag("monthly-preview")) { MonthlyReportsContent(entries,false,LocalDate.of(2027,1,17)) }
        } } }
        rule.waitUntil(10_000) { rule.onAllNodesWithText(formatMoney(9000)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("الشهر السابق").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText(formatMoney(7000)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(formatMoney(9000)).assertDoesNotExist()
        rule.onNodeWithText(formatMoney(3000)).assertIsDisplayed()
        rule.onNodeWithText("الشهر السابق • كانون الأول 2026").assertExists()
        val folder=File("build/reports/monthly-preview").apply{mkdirs()}
        val bitmap=rule.onNodeWithTag("monthly-preview").captureToImage().asAndroidBitmap()
        File(folder,"monthly-december.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }    @Test fun supplierMonthSeparatesOpeningBalanceMovementsAndShowsActualReceiptDetails() {
        fun row(day: String,amount: Long,kind: String="DEBT")=SupplierEntry(UUID.randomUUID().toString(),"مذخر النور",kind,amount,day,"وصل 123")
        val suppliers=listOf(row("2026-12-15",5000000),row("2027-01-10",2000000),row("2027-01-11",1000000,"PAYMENT"),row("2027-02-01",9000000))
        rule.setContent {PharmacyLedgerTheme {CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.testTag("monthly-preview")) {MonthlyReportsContent(emptyList(),false,LocalDate.of(2027,1,17),suppliers=suppliers)}
        }}}
        rule.waitUntil(10000) {rule.onAllNodesWithText(formatMoney(6000000)).fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText("المتبقي حتى نهاية الفترة").performScrollTo()
        rule.onNodeWithText(formatMoney(6000000)).assertIsDisplayed()
        rule.onNodeWithTag("monthly-list").performScrollToNode(hasText("تفاصيل حركات المذخر"))
        rule.onNodeWithText("تفاصيل حركات المذخر").performClick()
        rule.onNodeWithText("دين: ${formatMoney(2000000)}").assertExists()
        rule.onNodeWithText("تسديد: ${formatMoney(1000000)}").assertExists()
        rule.onNodeWithText("دين: ${formatMoney(9000000)}").assertDoesNotExist()
        rule.onNodeWithText("إغلاق").performClick()
        val bitmap=rule.onNodeWithTag("monthly-preview").captureToImage().asAndroidBitmap()
        File("build/reports/monthly-preview").apply {mkdirs()}.resolve("monthly-supplier-details.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }

}

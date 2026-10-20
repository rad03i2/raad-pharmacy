package com.radwan.raadpharmacy.ui.screens

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.radwan.raadpharmacy.data.*
import com.radwan.raadpharmacy.followup.*
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FollowupSmsCardTest {
    @get:Rule val rule=createComposeRule()
    @Test fun compactPeriodAndSmsCheckReflectActualMultipartOutcomeWithoutNavigatingOrMarkingWhatsapp() {
        val now=System.currentTimeMillis()
        val customer=Customer("preview","زبون تجريبي",phone="07701234567",createdAt=now-10*86_400_000L)
        val debt=LedgerEntry("debt",customer.id,EntryType.DEBT,250_000L,createdAt=customer.createdAt,repaymentDays=7)
        val account=DebtFollowupEngine.calculate(customer,listOf(debt)).copy(manuallyFollowed=true)
        var attempt by mutableStateOf<SmsAttempt?>(null)
        var sends=0;var opens=0;var shares=0
        rule.setContent {PharmacyLedgerTheme {CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.width(360.dp).testTag("followup-card-preview")) {FollowupAccountCard(account,false,false,false,null,attempt,false,now,{sends++},{shares++},{opens++})}
        }}}
        rule.onNodeWithText("أسبوع").assertExists()
        rule.onNodeWithText("تجاوز موعد التسديد المتفق عليه",substring=true).assertDoesNotExist()
        rule.onNodeWithText("SMS").performClick();rule.runOnIdle {assertEquals(1,sends);assertEquals(0,opens);assertEquals(0,shares)
            attempt=SmsAttempt("token","case",2,listOf(Activity.RESULT_OK,null),now,null)}
        rule.onNodeWithText("SMS ✓").assertDoesNotExist()
        rule.runOnIdle {attempt=attempt!!.copy(results=listOf(Activity.RESULT_OK,Activity.RESULT_OK),sentAt=now)}
        rule.onNodeWithText("SMS ✓").assertExists()
        rule.onNodeWithText("تم الإرسال • إعادة إرسال").assertDoesNotExist()
        val bitmap=rule.onNodeWithTag("followup-card-preview").captureToImage().asAndroidBitmap()
        File("build/reports/followup-preview").apply {mkdirs()}.resolve("followup-sms-sent.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        rule.runOnIdle {attempt=attempt!!.copy(results=listOf(Activity.RESULT_OK,4),sentAt=null)}
        rule.onNodeWithText("SMS ✓").assertDoesNotExist()
        rule.onNodeWithText("تعذر إرسال كل أجزاء SMS",substring=true).assertExists()
    }
}

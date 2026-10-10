package com.radwan.raadpharmacy.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import com.radwan.raadpharmacy.backup.*
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutomaticBackupScreenTest {
    @get:Rule val rule = createComposeRule()
    private val state = AutomaticBackupState(phone = AutomaticBackupPlace("phone", sequence = 10,
        updatedAt = 1791600000000, bytes = 1048576))
    private fun capture(name: String) {
        val folder = File("build/reports/backup-preview").apply { mkdirs() }
        rule.onNodeWithTag("auto-preview").captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun dashboardShowsOnlyThreePlacesLiveBackupInfoAndRestoreAtBottom() {
        var page by mutableStateOf(AutoBackupPage.HOME)
        val actions = mutableListOf<AutoBackupAction>()
        rule.setContent { PharmacyLedgerTheme(darkTheme = false) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.width(360.dp).height(760.dp).testTag("auto-preview")) {
                    AutomaticBackupContent(page, state, 10, false, false, emptyList(), { page = AutoBackupPage.HOME },
                        { page = it }, { actions.add(it) }, {})
                }
            }
        } }
        rule.onNodeWithText("حالة النسخ الاحتياطية").assertExists()
        rule.onNodeWithText("احفظ نسخة من بياناتك").assertDoesNotExist()
        rule.onNodeWithText("مفتاح الاسترداد").assertDoesNotExist()
        rule.onNodeWithText("داخل التطبيق").assertDoesNotExist()
        rule.onNodeWithTag("automatic-backup-list").performScrollToNode(hasTestTag("auto-restore"))
        rule.onNodeWithTag("auto-restore").assertIsDisplayed()
        capture("automatic-home-360")
        rule.onNodeWithTag("auto-restore").performClick()
        rule.onNodeWithText("اختيار ملف من الهاتف أو البطاقة").assertExists()
        rule.runOnIdle { page = AutoBackupPage.HOME }
        rule.onNodeWithTag("auto-phone").performClick()
        rule.onNodeWithText("نسخة تلقائية على الهاتف").assertExists()
        capture("automatic-phone-360")
        rule.runOnIdle { page = AutoBackupPage.SD }
        rule.onNodeWithTag("auto-sd-switch").performClick()
        assertEquals(AutoBackupAction.SD_TOGGLE, actions.last())
        capture("automatic-sd-360")
        rule.runOnIdle { page = AutoBackupPage.DRIVE }
        rule.onNodeWithText("ربط حساب Google").performClick()
        assertEquals(AutoBackupAction.GOOGLE_CONNECT, actions.last())
        capture("automatic-drive-360")
        rule.runOnIdle { page = AutoBackupPage.RESTORE }
        rule.onNodeWithText("اختيار ملف من الهاتف أو البطاقة").performClick()
        assertEquals(AutoBackupAction.IMPORT, actions.last())
        capture("automatic-restore-360")
    }
    @Test fun backgroundCopiesKeepNavigationUsableButUserActionLocksIt() {
        var working by mutableStateOf(false)
        rule.setContent { PharmacyLedgerTheme {
            AutomaticBackupContent(AutoBackupPage.HOME, state.copy(busy = true), 10, working, false, emptyList(), {}, {}, {}, {})
        } }
        rule.onNodeWithTag("auto-phone").assertIsEnabled()
        rule.runOnIdle { working = true }
        rule.onNodeWithTag("auto-phone").assertIsNotEnabled()
        rule.onNodeWithTag("automatic-backup-list").performScrollToNode(hasTestTag("auto-restore"))
        rule.onNodeWithTag("auto-restore").assertIsNotEnabled()
    }
    @Test fun driveShowsActualAccountPendingStatusAndReauthorizationWhenNeeded() {
        val cloud = state.copy(account = "backup@example.com", drive = AutomaticBackupPlace("drive", sequence = 8,
            updatedAt = 1000, error = "أعد ربط حساب Google للسماح بتحديث النسخة."))
        rule.setContent { PharmacyLedgerTheme {
            AutomaticBackupContent(AutoBackupPage.DRIVE, cloud, 10, false, false, emptyList(), {}, {}, {}, {})
        } }
        rule.onNodeWithText("backup@example.com").assertExists()
        rule.onNodeWithText("أعد ربط حساب Google للسماح بتحديث النسخة.").assertExists()
        rule.onNodeWithText("تجديد إذن حساب Google").assertIsEnabled()
    }
    @Test fun largeTextDarkModeKeepsBottomRestoreReachable() {
        rule.setContent { PharmacyLedgerTheme(darkTheme = true) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalDensity provides Density(1f, 1.5f)) {
                Box(Modifier.width(320.dp).height(640.dp).testTag("auto-preview")) {
                    AutomaticBackupContent(AutoBackupPage.HOME, state, 10, false, false, emptyList(), {}, {}, {}, {})
                }
            }
        } }
        rule.onNodeWithTag("automatic-backup-list").performScrollToNode(hasTestTag("auto-restore"))
        rule.onNodeWithTag("auto-restore").assertIsDisplayed()
        capture("automatic-large-text-dark-320")
    }
    @Test fun restoreHoldAndPreviewRemainExplicit() {
        val item = PortableBackupItem("phone", "uri", "copy", 1000, 500)
        var selected: PortableBackupItem? = null
        rule.setContent { PharmacyLedgerTheme {
            AutomaticBackupContent(AutoBackupPage.RESTORE, state, 10, false, true, listOf(item), {}, {}, {}, { selected = it })
        } }
        rule.onNodeWithText("البيانات المستعادة قيد المراجعة").assertExists()
        rule.onNodeWithText("معاينة هذه النسخة").performClick()
        assertEquals(item, selected)
    }
}

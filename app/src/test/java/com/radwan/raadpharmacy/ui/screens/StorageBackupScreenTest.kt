package com.radwan.raadpharmacy.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
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
import com.radwan.raadpharmacy.backup.BackupDestinationEntity
import com.radwan.raadpharmacy.backup.LocalBackupEngine
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
class StorageBackupScreenTest {
    @get:Rule val rule = createComposeRule()
    private val healthy = BackupDestinationEntity(LocalBackupEngine.PRIVATE, cursor = 10, lastFullAt = 1791600000000, bytes = 1048576)
    private fun capture(name: String) {
        val folder = File("build/reports/backup-preview").apply { mkdirs() }
        rule.onNodeWithTag("backup-preview").captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun dashboardSeparatesPlacesAndRestoreFlowWithoutTechnicalClutter() {
        var page by mutableStateOf(BackupPage.HOME)
        val actions = mutableListOf<BackupAction>()
        rule.setContent {
            PharmacyLedgerTheme(darkTheme = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.width(360.dp).height(760.dp).testTag("backup-preview")) {
                        StorageBackupContent(page, listOf(healthy), 10, false, false, false, emptyList(),
                            { page = BackupPage.HOME }, { page = it }, { actions.add(it) }, {})
                    }
                }
            }
        }
        rule.onNodeWithText("إنشاء نسخة الآن").assertIsEnabled().performClick()
        assertEquals(listOf(BackupAction.BACKUP), actions)
        rule.onNodeWithText("المصالحة مع الحساب المركزي").assertDoesNotExist()
        rule.onNodeWithText("التغييرات المعلقة").assertDoesNotExist()
        capture("backup-home-360")
        rule.onNodeWithTag("backup-phone").performClick()
        rule.onNodeWithTag("backup-list").performScrollToNode(hasText("اختيار مجلد النسخ"))
        // The action stays usable before key confirmation: the controller guides setup.
        rule.onNodeWithText("اختيار مجلد النسخ").assertIsEnabled().performClick()
        assertEquals(BackupAction.PHONE_FOLDER, actions.last())
        capture("backup-phone-360")
        rule.runOnIdle { page = BackupPage.SD }
        rule.onNodeWithTag("backup-sd-switch").assertIsOff().performClick()
        assertEquals(BackupAction.SD_TOGGLE, actions.last())
        capture("backup-sd-360")
        rule.runOnIdle { page = BackupPage.HOME }
        rule.onNodeWithText("استعادة نسخة").performClick()
        rule.onNodeWithTag("backup-import-file").performClick()
        assertEquals(BackupAction.IMPORT_FILE, actions.last())
        capture("backup-restore-360")
    }
    @Test fun busyStateLocksBackupAndNavigationUntilOperationCompletes() {
        rule.setContent { PharmacyLedgerTheme {
            StorageBackupContent(BackupPage.HOME, listOf(healthy), 10, true, true, false, emptyList(), {}, {}, {}, {})
        } }
        rule.onNodeWithText("إنشاء نسخة الآن").assertIsNotEnabled()
        rule.onNodeWithText("استعادة نسخة").assertIsNotEnabled()
        rule.onNodeWithTag("backup-phone").assertIsNotEnabled()
        rule.onNodeWithTag("backup-cloud").assertIsNotEnabled()
    }
    @Test fun unavailableSdWarnsWhilePrivateBackupRemainsHealthy() {
        val card = BackupDestinationEntity(LocalBackupEngine.SD, cursor = 5, lastFullAt = 10, error = "بطاقة الذاكرة غير متاحة")
        rule.setContent { PharmacyLedgerTheme {
            StorageBackupContent(BackupPage.SD, listOf(healthy, card), 10, false, true, false, emptyList(), {}, {}, {}, {})
        } }
        rule.onNodeWithText("يحتاج مراجعة").assertExists()
        rule.onNodeWithText("تغييرات تنتظر النسخ: 5").assertExists()
        rule.onNodeWithText("تغيير المجلد أو تجديد الإذن").assertIsEnabled()
    }
    @Test fun restorationHoldRemainsVisibleAndRequiresExplicitReconciliationAction() {
        val actions = mutableListOf<BackupAction>()
        rule.setContent { PharmacyLedgerTheme {
            StorageBackupContent(BackupPage.RESTORE, listOf(healthy), 10, false, true, true, emptyList(), {}, {}, { actions.add(it) }, {})
        } }
        rule.onNodeWithText("البيانات المستعادة قيد المراجعة").assertExists()
        rule.onNodeWithText("العودة إلى بيانات الصيدلية الحالية").performClick()
        assertEquals(listOf(BackupAction.RECONCILE), actions)
    }
    @Test fun largeTextAndDarkModeKeepRestoreActionsReachable() {
        rule.setContent { PharmacyLedgerTheme(darkTheme = true) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1.5f)) {
                Box(Modifier.width(320.dp).height(640.dp).testTag("backup-preview")) {
                    StorageBackupContent(BackupPage.RESTORE, listOf(healthy), 10, false, false, false, emptyList(), {}, {}, {}, {})
                }
            }
        } }
        rule.onNodeWithTag("backup-list").performScrollToNode(hasText("من ملف نسخة"))
        rule.onNodeWithText("من ملف نسخة").assertIsDisplayed()
        capture("backup-large-text-dark-320")
    }
    @Test fun writeResultNeverReportsSuccessForFailedOrPendingDestination() {
        val error = BackupDestinationEntity(LocalBackupEngine.SD, cursor = 10, lastFullAt = 1, error = "لا يوجد إذن")
        assertTrue(backupWriteResult(listOf(healthy, error), 10).startsWith("تعذر"))
        assertTrue(backupWriteResult(listOf(healthy.copy(cursor = 9)), 10).startsWith("تعذر"))
        assertTrue(backupWriteResult(listOf(healthy.copy(lastFullAt = 0)), 10).startsWith("تعذر"))
        assertTrue(backupWriteResult(emptyList(), 10).startsWith("تعذر"))
        assertTrue(backupWriteResult(listOf(healthy, error.copy(enabled = false)), 10).startsWith("تم إنشاء"))
        assertEquals(0L, backupDestinationStatus(error.copy(enabled = false), 10).pending)
    }
}

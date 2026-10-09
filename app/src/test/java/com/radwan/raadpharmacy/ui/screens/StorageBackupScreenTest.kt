package com.radwan.raadpharmacy.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import java.io.File
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
    @Test fun arabicBackupScreenShowsAllSectionsAndKeepsExternalExportLockedUntilKeyIsSaved() {
        rule.setContent {
            PharmacyLedgerTheme(darkTheme = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.width(360.dp).height(820.dp).testTag("backup-preview")) { StorageBackupScreen({}) }
                }
            }
        }
        rule.onNodeWithText("التخزين والنسخ الاحتياطي").assertExists()
        rule.onNodeWithText("حفظ مفتاح الاسترداد").assertExists()
        val folder = File("build/reports/backup-preview").apply { mkdirs() }
        rule.onNodeWithTag("backup-preview").captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder, "backup-phone-360.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        rule.onNodeWithTag("backup-list").performScrollToNode(hasText("إعداد أو تغيير مجلد النسخ على الهاتف"))
        rule.onNodeWithText("إعداد أو تغيير مجلد النسخ على الهاتف").assertIsNotEnabled()
        rule.onNodeWithTag("backup-list").performScrollToNode(hasText("بطاقة الذاكرة SD"))
        rule.onNodeWithText("النسخ إلى البطاقة معطل").assertExists()
        rule.onNodeWithTag("backup-list").performScrollToNode(hasText("الاستعادة وسجل النسخ"))
        rule.onNodeWithText("استيراد ملف مشفر أو نسخة JSON قديمة").assertExists()
    }
}

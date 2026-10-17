package com.radwan.raadpharmacy.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.radwan.raadpharmacy.backup.*
import com.radwan.raadpharmacy.data.*
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveryContentTest {
    @get:Rule val rule = createComposeRule()
    private fun plan(): RecoveryPlan {
        val customer = Customer("00000000-0000-4000-8000-000000000001", "اختبار", createdAt = 1000)
        return RecoveryPlan.build(BackupCapture(emptyList(), emptyList(), emptyList(), 0, false),
            RestoredArchive(BackupPayload(listOf(customer), emptyList(), 1000, 1), emptyList(), JSONObject(), 0))
    }
    @Test fun previewRequiresExplicitConfirmationAndShowsActualCounts() {
        var confirmations = 0
        rule.setContent { PharmacyLedgerTheme {
            RecoveryContent(RecoveryUiState(plan = plan(), sourceName = "fixture.json"), emptyList(),
                {}, {}, {}, { confirmations++ }, {}, {})
        } }
        rule.onNodeWithText("الملف: fixture.json").assertExists()
        rule.onNodeWithText("سجلات ستُضاف: 1 • موجودة ومطابقة: 0").assertExists()
        rule.onNodeWithText("تأكيد استرداد البيانات").performScrollTo().performClick()
        assertEquals(1, confirmations)
    }
    @Test fun processingAndCompletedResultsCannotBeConfirmedAgain() {
        rule.setContent { PharmacyLedgerTheme {
            RecoveryContent(RecoveryUiState(busy = true, plan = plan(), progress = "إنشاء نسخة الحماية…"),
                emptyList(), {}, {}, {}, { error("must not run") }, {}, {})
        } }
        rule.onNodeWithText("تأكيد استرداد البيانات").assertIsNotEnabled()
        rule.onNodeWithText("اختيار ملف من الهاتف أو البطاقة").assertIsNotEnabled()
    }
    @Test fun nineMillionComparisonAndConfirmationRenderInArabicOnAPhone() {
        val c = Customer("00000000-0000-4000-8000-000000000001", "اختبار", createdAt = 1000)
        fun debt(id: Int, amount: Long) = LedgerEntry("00000000-0000-4000-8000-" + id.toString().padStart(12, '0'),
            c.id, EntryType.DEBT, amount, createdAt = id * 1000L)
        val original = debt(2, 5_000_000)
        val input = RestoredArchive(BackupPayload(listOf(c),
            listOf(original, debt(3, 5_000_000), debt(4, 1_000_000).copy(type = EntryType.PAYMENT)), 5000, 1),
            emptyList(), JSONObject(), 0)
        val comparison = RecoveryPlan.build(BackupCapture(listOf(c.toEntity()), listOf(original.toEntity()), emptyList(), 0, false), input)
        rule.setContent { PharmacyLedgerTheme(darkTheme = false) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.width(360.dp).height(760.dp).testTag("recovery-preview")) {
                    RecoveryContent(RecoveryUiState(plan = comparison, sourceName = "نسخة الهاتف القديم.raadbackup"),
                        emptyList(), {}, {}, {}, {}, {}, {})
                }
            }
        } }
        fun capture(name: String) {
            val folder = File("build/reports/backup-preview").apply { mkdirs() }
            rule.onNodeWithTag("recovery-preview").captureToImage().asAndroidBitmap().let { bitmap ->
                File(folder, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        capture("recovery-comparison-360")
        rule.onNodeWithText("النسخة المختارة: 9,000,000 د.ع").assertExists()
        rule.onNodeWithText("تأكيد استرداد البيانات").performScrollTo().assertIsEnabled()
        capture("recovery-confirmation-360")
    }
}

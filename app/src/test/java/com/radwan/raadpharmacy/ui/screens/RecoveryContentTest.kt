package com.radwan.raadpharmacy.ui.screens

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
}

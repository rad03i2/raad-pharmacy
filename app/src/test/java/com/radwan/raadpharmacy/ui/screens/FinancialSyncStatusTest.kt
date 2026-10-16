package com.radwan.raadpharmacy.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.radwan.raadpharmacy.cloud.FinancialUploadState
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinancialSyncStatusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingAmountDoesNotClaimCloudConfirmationWhileSessionIsUnavailable() {
        compose.setContent { PharmacyLedgerTheme { FinancialSyncStatus(1, FinancialUploadState.WAITING_FOR_SESSION) } }
        compose.onNodeWithText("محفوظ على الهاتف: 1 عملية تنتظر استعادة الاتصال بالحساب").assertExists()
        compose.onNodeWithText("لا توجد عمليات مالية بانتظار الرفع").assertDoesNotExist()
    }

    @Test fun noPendingUploadsUsesDurableOutboxCountRatherThanLastAttemptState() {
        compose.setContent { PharmacyLedgerTheme { FinancialSyncStatus(0, FinancialUploadState.RETRYING) } }
        compose.onNodeWithText("لا توجد عمليات مالية بانتظار الرفع").assertExists()
    }
}

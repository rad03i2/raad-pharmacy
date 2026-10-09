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
import com.radwan.raadpharmacy.cloud.CentralBackupClient
import com.radwan.raadpharmacy.cloud.CentralBackupStatus
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35],qualifiers = "w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CentralCloudBackupScreenTest {
    @get:Rule val rule = createComposeRule()
    @Test fun unavailableServiceDoesNotOfferManualOrRestoreActions() {
        rule.setContent {
            PharmacyLedgerTheme(darkTheme = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.width(360.dp).height(820.dp).testTag("cloud-preview")) {
                        CentralCloudBackupContent(CentralBackupStatus(),false,null,{},{},{})
                    }
                }
            }
        }
        rule.onNodeWithText("الخدمة غير مهيأة بعد").assertExists()
        rule.onNodeWithText("طلب نسخة سحابية الآن").assertDoesNotExist()
        rule.onNodeWithText("استعادة الآن").assertDoesNotExist()
        val folder=File("build/reports/cloud-backup-preview").apply { mkdirs() }
        rule.onNodeWithTag("cloud-preview").captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder,"cloud-phone-360.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
    @Test fun staleServiceWarnsUserWithoutExposingAdminActions() {
        rule.setContent { PharmacyLedgerTheme {
            CentralCloudBackupContent(CentralBackupStatus(configured=true,health="STALE",lastSuccess="2026-10-01T00:00:00Z"),false,null,{},{},{})
        } }
        rule.onNodeWithText("تنبيه: لا توجد نسخة حديثة خلال 9 ساعات").assertExists()
        rule.onNodeWithText("إدارة النسخ").assertDoesNotExist()
    }
    @Test fun normalUserParsingDiscardsAdministrativeFieldsAndUsesEnglishDigits() {
        val status=CentralBackupClient.parse(JSONObject("""{"can_view_history":false,"can_request_backup":true,"owner_email":"private@example.invalid","last_error_code":"PRIVATE","history":[{"state":"FAILED"}]}"""))
        assertFalse(status.canRequest); assertTrue(status.history.isEmpty()); assertNull(status.owner); assertNull(status.error)
        assertEquals("2026-10-10  03:00",cloudDate("2026-10-10T00:00:00Z"))
        assertEquals("1.00 MB",cloudSize(1048576))
    }
    @Test fun authorizedAndReadyAdministratorCanRequestBackup() {
        rule.setContent { PharmacyLedgerTheme {
            CentralCloudBackupContent(CentralBackupStatus(configured=true,canViewHistory=true,canRequest=true),false,null,{},{},{})
        } }
        rule.onNodeWithTag("cloud-backup-list").performScrollToNode(hasText("طلب نسخة سحابية الآن"))
        rule.onNodeWithText("طلب نسخة سحابية الآن").assertIsEnabled()
    }
}

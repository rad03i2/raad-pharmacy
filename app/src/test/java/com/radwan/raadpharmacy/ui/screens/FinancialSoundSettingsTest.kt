package com.radwan.raadpharmacy.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.radwan.raadpharmacy.notifications.FinancialFeedbackSettings
import com.radwan.raadpharmacy.notifications.NotificationSoundPreset
import com.radwan.raadpharmacy.notifications.OperationSoundPreset
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinancialSoundSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsExposeOnlyOperationCompletionSoundToggle() {
        var enabled = true

        composeRule.setContent {
            MaterialTheme {
                FinancialSoundSettingsContent(
                    settings = FinancialFeedbackSettings(
                        operationSound = OperationSoundPreset.PIXABAY_OPERATION,
                        notificationSound = NotificationSoundPreset.PIXABAY_NOTIFICATION,
                        operationSoundEnabled = enabled
                    ),
                    onOperationSoundEnabledChange = { enabled = it }
                )
            }
        }

        composeRule.onNodeWithText("صوت اكتمال العملية").assertExists()
        composeRule.onNodeWithText("يعمل بعد نجاح تسجيل الدين أو التحصيل.").assertExists()
    }

    @Test
    fun toggleCanDisableOperationCompletionSound() {
        var enabled = true

        composeRule.setContent {
            MaterialTheme {
                FinancialSoundSettingsContent(
                    settings = FinancialFeedbackSettings(
                        operationSound = OperationSoundPreset.PIXABAY_OPERATION,
                        notificationSound = NotificationSoundPreset.PIXABAY_NOTIFICATION,
                        operationSoundEnabled = enabled
                    ),
                    onOperationSoundEnabledChange = { enabled = it }
                )
            }
        }

        composeRule.onNodeWithText("صوت اكتمال العملية").performClick()
        assertFalse(enabled)
    }
}

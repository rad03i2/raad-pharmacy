package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.radwan.raadpharmacy.notifications.FinancialFeedbackSettings
import com.radwan.raadpharmacy.notifications.NotificationSoundPreset
import com.radwan.raadpharmacy.notifications.OperationSoundPreset
import org.junit.Assert.assertEquals
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

    private val settings = FinancialFeedbackSettings(
        OperationSoundPreset.PIXABAY_OPERATION,
        NotificationSoundPreset.PIXABAY_NOTIFICATION
    )

    private var operationPreviews = 0
    private var notificationPreviews = 0
    private var popupTests = 0
    private var openedSettings = 0

    private fun showSettings() {
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FinancialSoundSettingsContent(
                        settings = settings,
                        onPreviewOperation = { operationPreviews++ },
                        onPreviewNotification = { notificationPreviews++ },
                        onOpenNotificationSettings = { openedSettings++ },
                        onTestNotification = { popupTests++ }
                    )
                }
            }
        }
    }

    @Test
    fun onlyTwoFixedPixabaySoundCardsAreShown() {
        showSettings()

        composeRule.onAllNodesWithText("صوت اكتمال العملية").onFirst().assertExists()
        composeRule.onAllNodesWithText("صوت التنبيه السحابي").onFirst().assertExists()
    }

    @Test
    fun bothFixedSoundsCanBePreviewed() {
        showSettings()

        composeRule.onAllNodesWithText("تجربة الصوت").onFirst().performClick()
        assertEquals(1, operationPreviews)

        composeRule.onAllNodesWithText("تجربة الصوت")[1].performScrollTo().performClick()
        assertEquals(1, notificationPreviews)
    }

    @Test
    fun externalNotificationControlsRemainAvailable() {
        showSettings()

        composeRule.onNodeWithText("تجربة إشعار خارجي").performScrollTo().performClick()
        composeRule.onNodeWithText("إعدادات إشعارات الهاتف").performScrollTo().performClick()

        assertEquals(1, popupTests)
        assertEquals(1, openedSettings)
    }
}

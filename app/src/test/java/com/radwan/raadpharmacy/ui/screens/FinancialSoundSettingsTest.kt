package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
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

    private val settings = mutableStateOf(
        FinancialFeedbackSettings(
            OperationSoundPreset.PIXABAY_OPERATION,
            NotificationSoundPreset.PIXABAY_NOTIFICATION
        )
    )
    private var previews = 0
    private var popupTests = 0
    private var openedSettings = 0

    private fun showSettings() {
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FinancialSoundSettingsContent(
                        settings = settings.value,
                        onOperationSelected = {
                            settings.value = settings.value.copy(operationSound = it)
                        },
                        onNotificationSelected = {
                            settings.value = settings.value.copy(notificationSound = it)
                        },
                        onPreviewOperation = { previews++ },
                        onPreviewNotification = { previews++ },
                        onOpenNotificationSettings = { openedSettings++ },
                        onTestNotification = { popupTests++ }
                    )
                }
            }
        }
    }

    @Test
    fun onlyTwoFixedSoundGroupsAreExposed() {
        showSettings()
        composeRule.onNodeWithText("صوت نجاح العملية").assertExists()
        composeRule.onNodeWithText("صوت إشعار الهاتف").assertExists()

        composeRule.onNodeWithText("صوت نجاح العملية").performClick()
        composeRule.onNodeWithText(OperationSoundPreset.PIXABAY_OPERATION.title).assertExists()

        composeRule.onNodeWithText("صوت إشعار الهاتف").performScrollTo().performClick()
        composeRule.onNodeWithText(NotificationSoundPreset.PIXABAY_NOTIFICATION.title).assertExists()
    }

    @Test
    fun previewAndPopupControlsRemainAvailable() {
        showSettings()
        composeRule.onNodeWithText("صوت إشعار الهاتف").performClick()
        composeRule.onAllNodesWithText("تجربة").onFirst().performScrollTo().performClick()
        assertEquals(1, previews)

        composeRule.onNodeWithText("تجربة إشعار منبثق").performScrollTo().performClick()
        composeRule.onNodeWithText("إعدادات التنبيه المنبثق").performScrollTo().performClick()
        assertEquals(1, popupTests)
        assertEquals(1, openedSettings)
    }
}

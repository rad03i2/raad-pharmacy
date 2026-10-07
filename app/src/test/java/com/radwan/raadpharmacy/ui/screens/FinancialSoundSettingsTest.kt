package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.radwan.raadpharmacy.notifications.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinancialSoundSettingsTest {
    @get:Rule val composeRule = createComposeRule()
    private val settings = mutableStateOf(FinancialFeedbackSettings(OperationSoundPreset.CASH_REGISTER, NotificationSoundPreset.CASH_PING))
    private var operationSelections = 0
    private var previews = 0
    private var popupTests = 0
    private var openedSettings = 0

    private fun showSettings(restoration: StateRestorationTester? = null) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FinancialSoundSettingsContent(settings.value,
                        onOperationSelected = { operationSelections++; settings.value = settings.value.copy(operationSound = it) },
                        onNotificationSelected = { settings.value = settings.value.copy(notificationSound = it) },
                        onPreviewOperation = { previews++ }, onPreviewNotification = { previews++ },
                        onOpenNotificationSettings = { openedSettings++ }, onTestNotification = { popupTests++ })
                }
            }
        }
        if (restoration == null) composeRule.setContent(content) else restoration.setContent(content)
    }

    @Test fun proposals_startHiddenAndCanBeCollapsedAgain() {
        showSettings()
        composeRule.onNodeWithText("صوت نجاح العملية").assertExists()
        composeRule.onNodeWithText("صوت إشعار الهاتف").assertExists()
        composeRule.onNodeWithText(OperationSoundPreset.GLASS.title).assertDoesNotExist()
        composeRule.onNodeWithText("تجربة إشعار منبثق").assertDoesNotExist()
        composeRule.onNodeWithText("صوت نجاح العملية").performClick()
        composeRule.onNodeWithText(OperationSoundPreset.GLASS.title).assertExists()
        composeRule.onNodeWithText("صوت نجاح العملية").performScrollTo().performClick()
        composeRule.onNodeWithText(OperationSoundPreset.GLASS.title).assertDoesNotExist()
    }

    @Test fun preview_doesNotSelectAndChoosingSoundDoesNotChangePhoneSound() {
        showSettings()
        composeRule.onNodeWithText("صوت نجاح العملية").performClick()
        composeRule.onAllNodesWithText("تجربة").onFirst().performScrollTo().performClick()
        assertEquals(1, previews)
        assertEquals(0, operationSelections)
        composeRule.onNodeWithText(OperationSoundPreset.GLASS.title).performScrollTo().performClick()
        assertEquals(1, operationSelections)
        assertEquals(OperationSoundPreset.GLASS, settings.value.operationSound)
        assertEquals(NotificationSoundPreset.CASH_PING, settings.value.notificationSound)
    }

    @Test fun restoredSettings_keepSoundSelectionButCloseProposals() {
        val restoration = StateRestorationTester(composeRule)
        showSettings(restoration)
        composeRule.onNodeWithText("صوت نجاح العملية").performClick()
        composeRule.onNodeWithText(OperationSoundPreset.GLASS.title).performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText(OperationSoundPreset.GLASS.title).assertDoesNotExist()
        composeRule.onNodeWithText("المختار: " + OperationSoundPreset.GLASS.title).assertExists()
        assertEquals(OperationSoundPreset.GLASS, settings.value.operationSound)
    }

    @Test fun openingPhoneSounds_closesSuccessSoundsAndExposesPopupControls() {
        showSettings()
        composeRule.onNodeWithText("صوت نجاح العملية").performClick()
        composeRule.onNodeWithText("صوت إشعار الهاتف").performScrollTo().performClick()
        composeRule.onNodeWithText("يعمل فور نجاح تسجيل الدين أو التحصيل أو التسديد الكامل.").assertDoesNotExist()
        composeRule.onNodeWithText(NotificationSoundPreset.REBOUND.title).assertExists()
        composeRule.onNodeWithText("تجربة إشعار منبثق").performScrollTo().performClick()
        composeRule.onNodeWithText("إعدادات التنبيه المنبثق").performScrollTo().performClick()
        assertEquals(1, popupTests)
        assertEquals(1, openedSettings)
    }
}

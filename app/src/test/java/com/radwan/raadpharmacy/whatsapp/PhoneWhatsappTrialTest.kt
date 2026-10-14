package com.radwan.raadpharmacy.whatsapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.ui.screens.PhoneWhatsappSendAction
import com.radwan.raadpharmacy.ui.screens.PhoneWhatsappTrialResult
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneWhatsappTrialTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun aDisconnectedHelperCannotSendEvenWithAValidRecipient() {
        var calls = 0
        composeRule.setContent { MaterialTheme { PhoneWhatsappSendAction(false, false, true) { calls++ } } }
        composeRule.onNodeWithText("إرسال صورة ونص تجريبيين").assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(0, calls) }
    }
    @Test fun submissionIsNotDisplayedAsSuccessfulSendingOrDelivery() {
        composeRule.setContent { MaterialTheme { PhoneWhatsappTrialResult("submitted") } }
        composeRule.onNodeWithText("سُجّل طلب التجربة؛ بانتظار تأكيد واتساب").assertExists()
        composeRule.onNodeWithText("تم تأكيد إرسال التجربة من واتساب").assertDoesNotExist()
        composeRule.onNodeWithText("تم تسليم التجربة إلى المستلم").assertDoesNotExist()
    }
    @Test fun theAttemptAndSecretSurviveReopeningButConfirmationIsBoundToTheSender() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("raad_phone_whatsapp_trial_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val first = PhoneWhatsappStore(context)
        val token = first.token()
        val id = UUID.randomUUID().toString()
        first.recordTrial(id)
        first.confirmReceived("9647701111111")
        val second = PhoneWhatsappStore(context)
        assertEquals(token, second.token())
        assertEquals(id, second.lastTrialId())
        assertTrue(second.verifiedAt("9647701111111") > 0L)
        assertEquals(0L, second.verifiedAt("9647702222222"))
        second.recordTrial(UUID.randomUUID().toString())
        assertEquals(0L, second.verifiedAt("9647701111111"))
    }
    @Test fun setupRejectsShellInputAndSyntheticMediaFitsTheBridgeLimits() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val image = PhoneWhatsappTestImage.create(context)
        assertTrue(image.image.size in 1..180_000)
        assertTrue(image.thumbnail.size in 1..25_000)
        assertEquals(0xff, image.image[0].toInt() and 255)
        assertEquals(0xd8, image.image[1].toInt() and 255)
        val command = PhoneWhatsappSetup.command(byteArrayOf(1, 2, 3), "a".repeat(64))
        assertTrue(command.contains("$" + "HOME/.raad-whatsapp"))
        assertTrue(command.contains("nodejs-lts"))
        assertTrue(command.contains("install.sh"))
        assertThrows(IllegalArgumentException::class.java) { PhoneWhatsappSetup.command(byteArrayOf(1), "'; touch /tmp/bad; '") }
    }
}

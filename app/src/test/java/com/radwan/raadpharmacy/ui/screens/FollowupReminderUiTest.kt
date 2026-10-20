package com.radwan.raadpharmacy.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FollowupReminderUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun confirmationRequiresExplicitSendingAndCancellationDoesNotCountAsSending() {
        var sent = 0
        var canceled = 0
        var alternative = 0
        composeRule.setContent {
            MaterialTheme {
                FollowupReminderConfirmation("أحمد", { sent++ }, { canceled++ }, { alternative++ })
            }
        }
        composeRule.onNodeWithText("لم أرسل").performClick()
        composeRule.runOnIdle { assertEquals(0, sent); assertEquals(1, canceled) }
        composeRule.onNodeWithText("مشاركة بديلة").performClick()
        composeRule.runOnIdle { assertEquals(0, sent); assertEquals(1, alternative) }
        composeRule.onNodeWithText("نعم، تم الإرسال").performClick()
        composeRule.runOnIdle { assertEquals(1, sent) }
    }

    @Test fun aConfirmedReminderHasAVisibleDatedBadge() {
        composeRule.setContent { MaterialTheme { FollowupReminderBadge(1_791_979_200_000L) } }
        composeRule.onNodeWithText("تم تذكيره", substring = true).assertExists()
    }

    @Test fun anUnconfirmedReminderHasNoSentBadge() {
        composeRule.setContent { MaterialTheme { FollowupReminderBadge(null) } }
        composeRule.onNodeWithText("تم تذكيره", substring = true).assertDoesNotExist()
    }
}

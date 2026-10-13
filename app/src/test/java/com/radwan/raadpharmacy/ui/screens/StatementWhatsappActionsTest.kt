package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
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
class StatementWhatsappActionsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun mainButtonRoutesThreeCustomersToDirectShareAndFallbackStaysExplicit() {
        val customer = mutableStateOf("first")
        val recipients = mutableListOf<String>()
        var chooserCount = 0
        composeRule.setContent {
            MaterialTheme {
                Column {
                    val recipient = customer.value
                    StatementWhatsappActions(false, true,
                        onDirectShare = { recipients.add(recipient) },
                        onChooseRecipient = { chooserCount++ })
                }
            }
        }
        for (id in listOf("first", "second", "third")) {
            composeRule.runOnIdle { customer.value = id }
            composeRule.onNodeWithText("مشاركة صورة الكشف والنص عبر واتساب").performClick()
        }
        composeRule.runOnIdle {
            assertEquals(listOf("first", "second", "third"), recipients)
            assertEquals(0, chooserCount)
        }
        composeRule.onNodeWithText("مشاركة بديلة واختيار الزبون").performClick()
        composeRule.runOnIdle { assertEquals(1, chooserCount) }
    }

    @Test fun missingPhoneCanStillShareWithoutInventingARecipient() {
        var directCount = 0
        var chooserCount = 0
        composeRule.setContent {
            MaterialTheme {
                Column {
                    StatementWhatsappActions(false, false,
                        onDirectShare = { directCount++ },
                        onChooseRecipient = { chooserCount++ })
                }
            }
        }
        composeRule.onNodeWithText("مشاركة صورة الكشف والنص عبر واتساب").performClick()
        composeRule.runOnIdle {
            assertEquals(0, directCount)
            assertEquals(1, chooserCount)
        }
    }

    @Test fun bothActionsAreDisabledWhileTheReceiptIsBeingPrepared() {
        composeRule.setContent {
            MaterialTheme {
                Column { StatementWhatsappActions(true, true, {}, {}) }
            }
        }
        composeRule.onNodeWithText("جاري تجهيز الكشف...").assertIsNotEnabled()
        composeRule.onNodeWithText("مشاركة بديلة واختيار الزبون").assertIsNotEnabled()
    }
}

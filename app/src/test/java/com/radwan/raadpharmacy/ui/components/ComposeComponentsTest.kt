package com.radwan.raadpharmacy.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.data.LedgerEntry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposeComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun customerCard_masksBalanceAndRemainsClickable() {
        var clicked = false
        val customer = Customer(
            id = "c1",
            name = "أحمد محمود",
            phone = "07700000000",
            area = "حي النور",
            openingDebt = 25_000L,
            createdAt = 1_000L
        )

        composeRule.setContent {
            MaterialTheme {
                CustomerCard(
                    customer = customer,
                    balance = 25_000L,
                    hideBalance = true,
                    onClick = { clicked = true }
                )
            }
        }

        composeRule.onNodeWithText("أحمد محمود").assertExists()
        composeRule.onNodeWithText("•••• د.ع").assertExists()
        composeRule.onNodeWithText("أحمد محمود").performClick()

        assertTrue(clicked)
    }

    @Test
    fun transactionRow_hidesSensitiveAmount() {
        val entry = LedgerEntry(
            id = "e1",
            customerId = "c1",
            type = EntryType.DEBT,
            amount = 25_000L,
            createdAt = 2_000L
        )

        composeRule.setContent {
            MaterialTheme {
                TransactionRow(
                    entry = entry,
                    showBalance = 40_000L,
                    hideAmounts = true
                )
            }
        }

        composeRule.onNodeWithText("دين").assertExists()
        composeRule.onNodeWithText("•••• د.ع").assertExists()
        composeRule.onNodeWithText("الرصيد •••• د.ع").assertExists()
    }
}

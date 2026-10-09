package com.radwan.raadpharmacy.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.cloud.CloudTeamProfileRow
import com.radwan.raadpharmacy.cloud.EntryActorStore
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.data.LedgerEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MovementAmountBadgeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun showsAuthorBelowDebtAmountInOneBadge() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = EntryActorStore.get(app)
        val entryId = "capsule-debt-3141"
        val userId = "raad-capsule-3141"
        store.rememberActor(entryId, userId)
        store.rememberProfiles(
            listOf(CloudTeamProfileRow(userId, "pharmacy-test", "رعد", "MANAGER"))
        )
        composeRule.setContent {
            MaterialTheme {
                MovementAmountBadge(LedgerEntry(entryId, "customer-1", EntryType.DEBT, 25000L))
            }
        }
        composeRule.onNodeWithText("+25,000 د.ع").assertExists()
        composeRule.onNodeWithText("رعد").assertExists()
    }

    @Test fun hidesPaymentAmountButKeepsCreatorName() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = EntryActorStore.get(app)
        val entryId = "capsule-payment-3141"
        val userId = "ahmed-capsule-3141"
        store.rememberActor(entryId, userId)
        store.rememberProfiles(
            listOf(CloudTeamProfileRow(userId, "pharmacy-test", "أحمد", "MANAGER"))
        )
        composeRule.setContent {
            MaterialTheme {
                MovementAmountBadge(
                    LedgerEntry(entryId, "customer-2", EntryType.PAYMENT, 10000L),
                    hideAmounts = true
                )
            }
        }
        composeRule.onNodeWithText("•••• د.ع").assertExists()
        composeRule.onNodeWithText("أحمد").assertExists()
    }
}

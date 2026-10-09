package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w420dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TeamMessageDialogsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unreadActionOnlyAppearsForNewMessagesAndAllActionsStayReachable() {
        var hasUnread by mutableStateOf(false)
        var sends=0; var alerts=0
        compose.setContent {
            PharmacyLedgerTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Row(Modifier.width(256.dp)) {
                        Text("أحمد",Modifier.weight(1f))
                        TeamMessageActions("أحمد",false,hasUnread,{alerts++},{sends++},{hasUnread=false})
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("رسائل جديدة من أحمد").assertDoesNotExist()
        compose.runOnIdle { hasUnread=true }
        listOf("تنبيه أحمد","إرسال رسالة إلى أحمد","رسائل جديدة من أحمد").forEach {
            compose.onNodeWithContentDescription(it).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithContentDescription("تنبيه أحمد").performClick()
        compose.onNodeWithContentDescription("إرسال رسالة إلى أحمد").performClick()
        compose.onNodeWithContentDescription("رسائل جديدة من أحمد").performClick()
        compose.onNodeWithContentDescription("رسائل جديدة من أحمد").assertDoesNotExist()
        assertEquals(1,sends); assertEquals(1,alerts)
    }
    @Test fun composingRejectsBlankAndOversizeMessagesAndKeepsTextWhenSendingFails() {
        // A focused text cursor keeps animating; drive frames explicitly instead of waiting for it to stop.
        compose.mainClock.autoAdvance = false
        var text by mutableStateOf("")
        var sent=0
        compose.setContent { PharmacyLedgerTheme {
            Column(Modifier.width(300.dp)) {
                TeamMessageInput(text,false,"تحقق من الإنترنت",{text=it})
                TeamMessageSendButton(text,false,{sent++})
            }
        } }
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithText("إرسال").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("صباح الخير")
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithText("إرسال").assertIsEnabled().performClick()
        assertEquals(1,sent)
        compose.onNode(hasSetTextAction()).assertTextContains("صباح الخير")
        compose.runOnIdle { text="س".repeat(501) }
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithText("إرسال").assertIsNotEnabled()
    }
}

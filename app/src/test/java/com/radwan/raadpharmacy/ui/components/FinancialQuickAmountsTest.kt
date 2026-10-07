package com.radwan.raadpharmacy.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.util.accumulatedAmount
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class FinancialQuickAmountsTest {
    @get:Rule val composeRule = createComposeRule()
    @Test fun nineValuesFitTwoRowsAndRepeatedTapAddsAtSmallWidth() {
        var amount = ""
        composeRule.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            FinancialQuickAmounts(true) { amount = accumulatedAmount(amount,it)!! }
        } } }
        val values = listOf("250","500","750","1000","5000","10000","15000","25000","50000")
        val nodes = values.map { composeRule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        assertEquals(2,nodes.map { it.top }.distinct().size)
        nodes.forEach { assertTrue(it.left>=0f); assertTrue(it.width>0f) }
        composeRule.onNodeWithText("500").performClick()
        composeRule.onNodeWithText("500").performClick()
        composeRule.onNodeWithText("1000").performClick()
        assertEquals("2000",amount)
    }
}

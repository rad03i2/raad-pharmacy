package com.radwan.raadpharmacy.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingLedgerNavigationTest {
    @get:Rule val composeRule = createComposeRule()
    private val items = listOf(LedgerNavigationItem("home","الرئيسية",Icons.Rounded.Home),
        LedgerNavigationItem("customers","الزبائن",Icons.Rounded.Groups),
        LedgerNavigationItem("collections","التحصيلات",Icons.Rounded.AccountBalanceWallet),
        LedgerNavigationItem("reports","التقارير",Icons.Rounded.BarChart),
        LedgerNavigationItem("settings","الضبط",Icons.Rounded.Tune))

    @Test fun onlySelectedLabelAppearsAndEveryTabCanBeReached() {
        var selected by mutableStateOf("home")
        composeRule.setContent {
            PharmacyLedgerTheme(darkTheme = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.width(360.dp).height(112.dp).background(MaterialTheme.colorScheme.background).testTag("preview")) {
                        FloatingLedgerNavigation(items, selected, { selected = it })
                    }
                }
            }
        }
        items.forEach { target ->
            composeRule.onNodeWithContentDescription(target.label).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription(target.label).assertIsSelected()
            composeRule.onNodeWithText(target.label).assertExists()
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(target.label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            println("LABEL ${target.label}: size=${layout.size}; widthOverflow=${layout.didOverflowWidth}; heightOverflow=${layout.didOverflowHeight}; paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; lines=${layout.lineCount}; ellipsized=${layout.isLineEllipsized(0)}")
            savePreview("navigation-current-${target.route}.png")
            org.junit.Assert.assertFalse("Selected label must fit", layout.hasVisualOverflow)
            items.filter { it != target }.forEach { composeRule.onNodeWithText(it.label).assertDoesNotExist() }
            items.forEach { composeRule.onNodeWithContentDescription(it.label)
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp) }
            if (target.route == "collections") savePreview("navigation-light-360.png")
        }
    }
    @Test fun darkNarrowBarSupportsLargerArabicTextAndRtl() {
        composeRule.setContent {
            PharmacyLedgerTheme(darkTheme = true, textScale = 1.35f) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.width(320.dp).height(120.dp).background(MaterialTheme.colorScheme.background).testTag("preview")) {
                        FloatingLedgerNavigation(items, "collections", {})
                    }
                }
            }
        }
        composeRule.onNodeWithText("التحصيلات").assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("التحصيلات").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        println("LARGE LABEL: size=${layout.size}; widthOverflow=${layout.didOverflowWidth}; heightOverflow=${layout.didOverflowHeight}; paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; lines=${layout.lineCount}; ellipsized=${layout.isLineEllipsized(0)}")
        savePreview("navigation-dark-320.png")
        org.junit.Assert.assertFalse("Larger Arabic text must fit", layout.hasVisualOverflow)
        composeRule.onNodeWithText("الرئيسية").assertDoesNotExist()
        items.forEach { composeRule.onNodeWithContentDescription(it.label)
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp) }
        val home = composeRule.onNodeWithContentDescription("الرئيسية").fetchSemanticsNode().boundsInRoot
        val settings = composeRule.onNodeWithContentDescription("الضبط").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(home.left > settings.left)
        savePreview("navigation-dark-320.png")
    }
    private fun savePreview(name: String) {
        val folder = File("build/reports/navigation-preview").apply { mkdirs() }
        val bitmap = composeRule.onNodeWithTag("preview").captureToImage().asAndroidBitmap()
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}

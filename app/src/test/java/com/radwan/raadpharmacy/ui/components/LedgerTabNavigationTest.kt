package com.radwan.raadpharmacy.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w420dp-h900dp-mdpi")
class LedgerTabNavigationTest {
    @get:Rule val rule=createComposeRule()
    private val routes=listOf("home","customers","collections","reports","medicine_shopping","settings")
    @Test fun switchingTabsRestoresSearchAndMidListInsteadOfReopeningAtTheTop() {
        rule.setContent {
            val nav=rememberNavController()
            MaterialTheme { Column {
                NavHost(nav,"home",Modifier.weight(1f),enterTransition={EnterTransition.None},exitTransition={ExitTransition.None}) {
                    routes.forEach { route -> composable(route) {
                        var text by rememberSaveable {mutableStateOf("")}
                        Column { TextField(text,{text=it},Modifier.testTag("query-$route"))
                            LazyColumn(state=rememberLazyListState(),modifier=Modifier.testTag("list-$route")) {
                                items((0..99).toList()) {Text("$route row $it",Modifier.fillMaxWidth().height(64.dp))}
                            }
                        }
                    } }
                }
                Row {routes.forEach {route->TextButton(onClick={nav.navigateLedgerTab(route)},modifier=Modifier.testTag("tab-$route")){Text(route.take(2))}}}
            } }
        }
        rule.onNodeWithTag("tab-customers").performClick()
        rule.onNodeWithTag("query-customers").performTextInput("محفوظ")
        rule.onNodeWithTag("list-customers").performScrollToIndex(50)
        rule.onNodeWithTag("tab-medicine_shopping").performClick()
        rule.onNodeWithTag("tab-settings").performClick()
        rule.onNodeWithTag("tab-customers").performClick()
        rule.onNodeWithTag("query-customers").assertTextEquals("محفوظ")
        rule.onNodeWithText("customers row 50").assertIsDisplayed()
        rule.onNodeWithText("customers row 0").assertDoesNotExist()
    }
}

package com.radwan.raadpharmacy

import android.app.Activity
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.radwan.raadpharmacy.cloud.CloudActivityBannerHost
import com.radwan.raadpharmacy.cloud.CloudSyncRuntime
import com.radwan.raadpharmacy.ui.screens.AddCustomerScreenV12
import com.radwan.raadpharmacy.ui.screens.AppLockScreenV9
import com.radwan.raadpharmacy.ui.screens.AddDebtScreenV12
import com.radwan.raadpharmacy.ui.screens.AddPaymentScreenV12
import com.radwan.raadpharmacy.ui.screens.AreasScreenV4
import com.radwan.raadpharmacy.ui.screens.CustomerProfileScreenV6
import com.radwan.raadpharmacy.ui.screens.CustomerTransactionsScreenV6
import com.radwan.raadpharmacy.ui.screens.CustomersScreenV3
import com.radwan.raadpharmacy.ui.screens.DailyCollectionsScreenV4
import com.radwan.raadpharmacy.ui.screens.DailyDebtsScreenV4
import com.radwan.raadpharmacy.ui.screens.FollowUpScreenV4
import com.radwan.raadpharmacy.ui.screens.HomeScreenV3
import com.radwan.raadpharmacy.ui.screens.ReportsScreenV11
import com.radwan.raadpharmacy.ui.screens.SettingsScreenV10
import com.radwan.raadpharmacy.ui.screens.SmartSearchScreenV12
import com.radwan.raadpharmacy.ui.screens.SearchIntentV12
import com.radwan.raadpharmacy.ui.screens.StatementScreenV7
import com.radwan.raadpharmacy.ui.screens.TopDebtorsScreenV4
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme

object Routes {
    const val HOME = "home"
    const val CUSTOMERS = "customers"
    const val ADD_CUSTOMER = "add_customer"
    const val CUSTOMER = "customer/{customerId}"
    const val ADD_DEBT = "add_debt/{customerId}"
    const val ADD_PAYMENT = "add_payment/{customerId}"
    const val TRANSACTIONS = "transactions/{customerId}"
    const val STATEMENT = "statement/{customerId}"
    const val COLLECTIONS = "collections"
    const val DAILY_DEBTS = "daily_debts"
    const val TOP_DEBTORS = "top_debtors"
    const val AREAS = "areas"
    const val SEARCH = "search?intent={intent}"
    const val REPORTS = "reports"
    const val FOLLOWUP = "followup"
    const val SETTINGS = "settings"

    fun customer(id: String) = "customer/$id"
    fun addDebt(id: String) = "add_debt/$id"
    fun addPayment(id: String) = "add_payment/$id"
    fun transactions(id: String) = "transactions/$id"
    fun statement(id: String) = "statement/$id"
    fun search(intent: String = "open") = "search?intent=$intent"
}

private data class BottomDestination(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun PharmacyLedgerApp(
    vm: PharmacyLedgerViewModel = viewModel(),
    notificationCustomerId: String? = null,
    onNotificationHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val security by vm.securityState.collectAsStateWithLifecycle()
    val typography by vm.typographySettings.collectAsStateWithLifecycle()
    val unlocked by vm.isUnlocked.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    LaunchedEffect(notificationCustomerId, unlocked) {
        val customerId = notificationCustomerId
        if (unlocked && !customerId.isNullOrBlank()) {
            if (vm.customer(customerId) != null) {
                navController.navigate(Routes.customer(customerId)) {
                    launchSingleTop = true
                }
            }
            onNotificationHandled()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    vm.onAppForegrounded()
                    CloudSyncRuntime.onAppForegrounded(context)
                }
                Lifecycle.Event.ON_STOP -> {
                    vm.onAppBackgrounded()
                    CloudSyncRuntime.onAppBackgrounded()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SideEffect {
        (context as? Activity)?.window?.let { window ->
            if (security.secureScreen) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    val bottomItems = listOf(
        BottomDestination(Routes.HOME, "الرئيسية", Icons.Rounded.Home),
        BottomDestination(Routes.CUSTOMERS, "الزبائن", Icons.Rounded.People),
        BottomDestination(Routes.COLLECTIONS, "التحصيلات", Icons.Rounded.Payments),
        BottomDestination(Routes.REPORTS, "التقارير", Icons.Rounded.Assessment),
        BottomDestination(Routes.SETTINGS, "المزيد", Icons.Rounded.Settings)
    )
    val bottomRoutes = bottomItems.map { it.route }.toSet()
    var lastNavigationAt by remember { mutableLongStateOf(0L) }

    fun safeNavigate(route: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNavigationAt < 280L) return
        lastNavigationAt = now
        navController.navigate(route) {
            launchSingleTop = true
        }
    }

    if (currentRoute != null && currentRoute in bottomRoutes && currentRoute != Routes.HOME) {
        BackHandler {
            navController.navigate(Routes.HOME) {
                popUpTo(navController.graph.findStartDestination().id) {
                    inclusive = false
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    PharmacyLedgerTheme(
        font = typography.font,
        textScale = typography.scale
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            if (security.pinEnabled && !unlocked) {
                AppLockScreenV9(vm)
            } else {
                Scaffold(
                bottomBar = {
                    if (currentRoute != null && currentRoute in bottomRoutes) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp
                        ) {
                            bottomItems.forEach { item ->
                                NavigationBarItem(
                                    selected = currentRoute == item.route,
                                    onClick = {
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastNavigationAt < 280L) return@NavigationBarItem
                                        lastNavigationAt = now
                                        navController.navigate(item.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(item.icon, contentDescription = item.label) },
                                    label = { Text(item.label, style = MaterialTheme.typography.labelMedium) },
                                    alwaysShowLabel = false,
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                val swipeThresholdPx = with(LocalDensity.current) { 52.dp.toPx() }

                NavHost(
                    navController = navController,
                    startDestination = Routes.HOME,
                    modifier = Modifier
                        .padding(innerPadding)
                        .pointerInput(currentRoute, swipeThresholdPx) {
                            if (currentRoute !in bottomRoutes) return@pointerInput

                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var totalX = 0f
                                var totalY = 0f
                                var finished = false

                                while (!finished) {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: break
                                    val delta = change.positionChange()
                                    totalX += delta.x
                                    totalY += delta.y
                                    finished = !change.pressed
                                }

                                val horizontalEnough =
                                    kotlin.math.abs(totalX) >= swipeThresholdPx &&
                                        kotlin.math.abs(totalX) >
                                        kotlin.math.abs(totalY) * 1.35f

                                if (horizontalEnough) {
                                    val index = bottomItems.indexOfFirst {
                                        it.route == currentRoute
                                    }
                                    if (index >= 0) {
                                        // RTL: dragging right advances visually to the next tab.
                                        val target = if (totalX > 0f) {
                                            (index + 1).coerceAtMost(bottomItems.lastIndex)
                                        } else {
                                            (index - 1).coerceAtLeast(0)
                                        }
                                        val route = bottomItems[target].route
                                        if (route != currentRoute) {
                                            val now = SystemClock.elapsedRealtime()
                                            if (now - lastNavigationAt >= 280L) {
                                                lastNavigationAt = now
                                                navController.navigate(route) {
                                                    popUpTo(
                                                        navController.graph
                                                            .findStartDestination().id
                                                    ) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    composable(Routes.HOME) {
                        HomeScreenV3(
                            vm = vm,
                            onCustomers = { safeNavigate(Routes.CUSTOMERS) },
                            onAddCustomer = { safeNavigate(Routes.ADD_CUSTOMER) },
                            onSearch = { navController.navigate(Routes.search("open")) { launchSingleTop = true } },
                            onQuickDebt = { navController.navigate(Routes.search("debt")) { launchSingleTop = true } },
                            onQuickPayment = { navController.navigate(Routes.search("payment")) { launchSingleTop = true } },
                            onCollections = { safeNavigate(Routes.COLLECTIONS) },
                            onDailyDebts = { safeNavigate(Routes.DAILY_DEBTS) },
                            onTopDebtors = { safeNavigate(Routes.TOP_DEBTORS) },
                            onAreas = { safeNavigate(Routes.AREAS) },
                            onFollowUp = { safeNavigate(Routes.FOLLOWUP) },
                            onCustomer = { safeNavigate(Routes.customer(it)) }
                        )
                    }
                    composable(Routes.CUSTOMERS) {
                        CustomersScreenV3(
                            vm = vm,
                            onAdd = { navController.navigate(Routes.ADD_CUSTOMER) },
                            onCustomer = { navController.navigate(Routes.customer(it)) }
                        )
                    }
                    composable(Routes.ADD_CUSTOMER) {
                        AddCustomerScreenV12(
                            vm = vm,
                            onBack = navController::popBackStack,
                            onSaved = { navController.navigate(Routes.customer(it)) {
                                popUpTo(Routes.ADD_CUSTOMER) { inclusive = true }
                            } }
                        )
                    }
                    composable(
                        Routes.CUSTOMER,
                        arguments = listOf(navArgument("customerId") { type = NavType.StringType })
                    ) {
                        val id = it.arguments?.getString("customerId").orEmpty()
                        CustomerProfileScreenV6(
                            vm = vm,
                            customerId = id,
                            onBack = navController::popBackStack,
                            onDeleted = {
                                navController.navigate(Routes.CUSTOMERS) {
                                    popUpTo(Routes.CUSTOMERS) { inclusive = false }
                                    launchSingleTop = true
                                }
                            },
                            onAddDebt = {
                                navController.navigate(Routes.addDebt(id)) { launchSingleTop = true }
                            },
                            onPayment = {
                                navController.navigate(Routes.addPayment(id)) { launchSingleTop = true }
                            },
                            onTransactions = {
                                navController.navigate(Routes.transactions(id)) { launchSingleTop = true }
                            },
                            onStatement = {
                                navController.navigate(Routes.statement(id)) { launchSingleTop = true }
                            }
                        )
                    }
                    composable(
                        Routes.ADD_DEBT,
                        arguments = listOf(navArgument("customerId") { type = NavType.StringType })
                    ) {
                        val id = it.arguments?.getString("customerId").orEmpty()
                        AddDebtScreenV12(vm, id, navController::popBackStack)
                    }
                    composable(
                        Routes.ADD_PAYMENT,
                        arguments = listOf(navArgument("customerId") { type = NavType.StringType })
                    ) {
                        val id = it.arguments?.getString("customerId").orEmpty()
                        AddPaymentScreenV12(vm, id, navController::popBackStack)
                    }
                    composable(
                        Routes.TRANSACTIONS,
                        arguments = listOf(navArgument("customerId") { type = NavType.StringType })
                    ) {
                        val id = it.arguments?.getString("customerId").orEmpty()
                        CustomerTransactionsScreenV6(vm, id, navController::popBackStack)
                    }
                    composable(
                        Routes.STATEMENT,
                        arguments = listOf(navArgument("customerId") { type = NavType.StringType })
                    ) {
                        val id = it.arguments?.getString("customerId").orEmpty()
                        StatementScreenV7(vm, id, navController::popBackStack)
                    }
                    composable(Routes.COLLECTIONS) {
                        DailyCollectionsScreenV4(vm, onCustomer = { navController.navigate(Routes.customer(it)) })
                    }
                    composable(Routes.DAILY_DEBTS) {
                        DailyDebtsScreenV4(vm, navController::popBackStack, onCustomer = { navController.navigate(Routes.customer(it)) })
                    }
                    composable(Routes.TOP_DEBTORS) {
                        TopDebtorsScreenV4(vm, navController::popBackStack, onCustomer = { navController.navigate(Routes.customer(it)) })
                    }
                    composable(Routes.AREAS) {
                        AreasScreenV4(vm, navController::popBackStack, onCustomer = { navController.navigate(Routes.customer(it)) })
                    }
                    composable(
                        Routes.SEARCH,
                        arguments = listOf(
                            navArgument("intent") {
                                type = NavType.StringType
                                defaultValue = "open"
                            }
                        )
                    ) {
                        val intent = SearchIntentV12.fromRoute(
                            it.arguments?.getString("intent")
                        )
                        SmartSearchScreenV12(
                            vm = vm,
                            intent = intent,
                            onBack = navController::popBackStack,
                            onCustomer = { id ->
                                navController.navigate(Routes.customer(id)) {
                                    launchSingleTop = true
                                }
                            },
                            onDebt = { id ->
                                navController.navigate(Routes.addDebt(id)) {
                                    launchSingleTop = true
                                }
                            },
                            onPayment = { id ->
                                navController.navigate(Routes.addPayment(id)) {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                    composable(Routes.REPORTS) {
                        ReportsScreenV11(vm, onCustomer = { navController.navigate(Routes.customer(it)) })
                    }
                    composable(Routes.FOLLOWUP) {
                        FollowUpScreenV4(vm, navController::popBackStack, onCustomer = { navController.navigate(Routes.customer(it)) })
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreenV10(vm)
                    }
                }
            }

            CloudActivityBannerHost(
                onCustomerClick = { customerId ->
                    if (unlocked && vm.customer(customerId) != null) {
                        safeNavigate(Routes.customer(customerId))
                    }
                }
            )
            }
        }
    }
}

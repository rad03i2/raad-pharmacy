package com.radwan.raadpharmacy

import android.app.Activity
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
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
import com.radwan.raadpharmacy.data.FormDraftStore
import com.radwan.raadpharmacy.ui.components.FloatingLedgerNavigation
import com.radwan.raadpharmacy.ui.components.LedgerNavigationItem
import com.radwan.raadpharmacy.ui.components.navigateLedgerTab
import com.radwan.raadpharmacy.ui.screens.AddCustomerScreenV12
import com.radwan.raadpharmacy.ui.screens.AppLockScreenV9
import com.radwan.raadpharmacy.ui.screens.AddDebtScreenV12
import com.radwan.raadpharmacy.ui.screens.AddPaymentScreenV12
import com.radwan.raadpharmacy.ui.screens.AreaCustomersScreenV4
import com.radwan.raadpharmacy.ui.screens.AreasScreenV4
import com.radwan.raadpharmacy.ui.screens.CustomerProfileScreenV6
import com.radwan.raadpharmacy.ui.screens.CustomerTransactionsScreenV6
import com.radwan.raadpharmacy.ui.screens.CustomersScreenV3
import com.radwan.raadpharmacy.ui.screens.DailyCollectionsScreenV4
import com.radwan.raadpharmacy.ui.screens.DailyDebtsScreenV4
import com.radwan.raadpharmacy.ui.screens.SmartDebtFollowupV316
import com.radwan.raadpharmacy.ui.screens.HomeScreenV3
import com.radwan.raadpharmacy.ui.screens.MonthlyReportsScreen
import com.radwan.raadpharmacy.ui.screens.MedicineShoppingScreen
import com.radwan.raadpharmacy.ui.screens.ReportsScreenV11
import com.radwan.raadpharmacy.ui.screens.SettingsScreenV10
import com.radwan.raadpharmacy.ui.screens.AllActivityScreen
import com.radwan.raadpharmacy.ui.screens.SmartSearchScreenV12
import com.radwan.raadpharmacy.ui.screens.SearchIntentV12
import com.radwan.raadpharmacy.ui.screens.StatementScreenV7
import com.radwan.raadpharmacy.ui.screens.TopDebtorsScreenV4
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme

object Routes {
    const val HOME = "home"
    const val ACTIVITY = "activity"
    const val CUSTOMERS = "customers"
    const val ADD_CUSTOMER = "add_customer"
    const val CUSTOMER = "customer/{customerId}?editPhone={editPhone}"
    const val ADD_DEBT = "add_debt/{customerId}"
    const val ADD_PAYMENT = "add_payment/{customerId}"
    const val TRANSACTIONS = "transactions/{customerId}"
    const val STATEMENT = "statement/{customerId}"
    const val COLLECTIONS = "collections"
    const val DAILY_DEBTS = "daily_debts"
    const val TOP_DEBTORS = "top_debtors"
    const val AREAS = "areas"
    const val AREA_CUSTOMERS = "area_customers/{areaName}"
    const val SEARCH = "search?intent={intent}"
    const val REPORTS = "reports"
    const val SHOPPING = "medicine_shopping"
    const val MONTHLY_REPORTS = "monthly_reports"
    const val FOLLOWUP = "followup"
    const val SETTINGS = "settings"
    const val RESUME_FORM = "resume_form"

    fun areaCustomers(name: String) = "area_customers/${android.net.Uri.encode(name)}"
    fun customer(id: String, editPhone: Boolean = false) =
        "customer/$id" + if (editPhone) "?editPhone=true" else ""
    fun addDebt(id: String) = "add_debt/$id"
    fun addPayment(id: String) = "add_payment/$id"
    fun transactions(id: String) = "transactions/$id"
    fun statement(id: String) = "statement/$id"
    fun search(intent: String = "open") = "search?intent=$intent"
}

@Composable
fun PharmacyLedgerApp(
    vm: PharmacyLedgerViewModel = viewModel(),
    shoppingShareId: String? = null,
    onShoppingShareHandled: ()->Unit = {},
    notificationCustomerId: String? = null,
    notificationOpenSettings: Boolean = false,
    notificationOpenFollowup: Boolean = false,
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
    // If Android recreates the process without a restorable Navigation back stack,
    // reopen the active form from its durable local draft instead of dropping to Home.
    val restoredFormRoute = remember(context) { FormDraftStore.activeRoute(context) }
    // Re-check the durable route whenever the app unlocks or the navigation graph
    // changes. Intentional exits clear the active route before navigation.
    LaunchedEffect(unlocked, currentRoute) {
        if (!unlocked || currentRoute == null) return@LaunchedEffect
        if (
            currentRoute == Routes.RESUME_FORM ||
            currentRoute == Routes.ADD_CUSTOMER ||
            currentRoute == Routes.ADD_DEBT ||
            currentRoute == Routes.ADD_PAYMENT
        ) return@LaunchedEffect
        val active = FormDraftStore.activeRoute(context) ?: return@LaunchedEffect
        navController.navigate(active) { launchSingleTop = true }
    }

    LaunchedEffect(shoppingShareId,unlocked) {
        if(shoppingShareId!=null && unlocked) navController.navigateLedgerTab(Routes.SHOPPING)
    }

    LaunchedEffect(notificationCustomerId, notificationOpenSettings, notificationOpenFollowup, unlocked) {
        if (unlocked && notificationOpenFollowup) {
            FormDraftStore.clearActiveDraft(context)
            navController.navigate("${Routes.FOLLOWUP}?showAll=true") { launchSingleTop = true }
            onNotificationHandled()
            return@LaunchedEffect
        }
        if (unlocked && notificationOpenSettings) {
            FormDraftStore.clearActiveDraft(context)
            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
            onNotificationHandled()
            return@LaunchedEffect
        }
        val customerId = notificationCustomerId
        if (unlocked && !customerId.isNullOrBlank()) {
            if (vm.customer(customerId) != null) {
                FormDraftStore.clearActiveDraft(context)
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
                    FormDraftStore.flush(context)
                    vm.onAppBackgrounded()
                    CloudSyncRuntime.onAppBackgrounded(context)
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

    val bottomItems = remember { listOf(
        LedgerNavigationItem(Routes.HOME, "الرئيسية", Icons.Rounded.Home),
        LedgerNavigationItem(Routes.CUSTOMERS, "الزبائن", Icons.Rounded.Groups),
        LedgerNavigationItem(Routes.COLLECTIONS, "التحصيلات", Icons.Rounded.AccountBalanceWallet),
        LedgerNavigationItem(Routes.REPORTS, "التقارير", Icons.Rounded.BarChart),
        LedgerNavigationItem(Routes.SHOPPING, "الشراء", Icons.Rounded.ShoppingBag),
        LedgerNavigationItem(Routes.SETTINGS, "الضبط", Icons.Rounded.Settings)
    ) }
    val bottomRoutes = remember(bottomItems) { bottomItems.map { it.route }.toSet() }
    var lastNavigationAt by remember { mutableLongStateOf(0L) }

    fun navigateHome() {
        // Re-establish HOME even when a deletion or a restored form removed the
        // start destination from the back stack. Never rely on popBackStack() alone.
        navController.navigate(Routes.HOME) {
            popUpTo(navController.graph.id) { inclusive = false }
            launchSingleTop = true
        }
    }

    fun navigateTab(route: String) { navController.navigateLedgerTab(route) }

    fun backOrHome() {
        if (!navController.popBackStack()) navigateHome()
    }

    fun safeNavigate(route: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNavigationAt < 280L) return
        lastNavigationAt = now
        navController.navigate(route) {
            launchSingleTop = true
        }
    }

    if (unlocked && (
        currentRoute == Routes.ADD_CUSTOMER ||
            currentRoute == Routes.ADD_DEBT ||
            currentRoute == Routes.ADD_PAYMENT
        )
    ) {
        BackHandler {
            FormDraftStore.clearActiveDraft(context)
            backOrHome()
        }
    } else if (currentRoute != null && currentRoute in bottomRoutes && currentRoute != Routes.HOME) {
        BackHandler { navigateHome() }
    } else if (unlocked && currentRoute != null && currentRoute != Routes.HOME) {
        // Secondary screens can be opened from search, notifications or restored drafts.
        // Fall back to HOME rather than leaving a dead-end screen.
        BackHandler { backOrHome() }
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
                        FloatingLedgerNavigation(items = bottomItems, selectedRoute = currentRoute,
                            onSelect = ::navigateTab)
                    }
                }
            ) { innerPadding ->
                val swipeThresholdPx = with(LocalDensity.current) { 52.dp.toPx() }

                NavHost(
                    navController = navController,
                    enterTransition = { if (initialState.destination.route in bottomRoutes && targetState.destination.route in bottomRoutes)
                        EnterTransition.None else androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) },
                    exitTransition = { if (initialState.destination.route in bottomRoutes && targetState.destination.route in bottomRoutes)
                        ExitTransition.None else androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) },
                    startDestination = if (restoredFormRoute != null) Routes.RESUME_FORM else Routes.HOME,
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
                                        if (route != currentRoute) navigateTab(route)
                                    }
                                }
                            }
                        }
                ) {
                    composable(Routes.RESUME_FORM) {
                        LaunchedEffect(Unit) {
                            // Read the live preference: the route captured at app start may
                            // have been cleared after the user intentionally left the form.
                            val target = FormDraftStore.activeRoute(context)
                            navController.navigate(target ?: Routes.HOME) {
                                popUpTo(Routes.RESUME_FORM) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                        Text("جارٍ استعادة النموذج السابق...")
                    }
                    composable(Routes.HOME) {
                        HomeScreenV3(
                            vm = vm,
                            onCustomers = { safeNavigate(Routes.CUSTOMERS) },
                            onActivity = { safeNavigate(Routes.ACTIVITY) },
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
                    composable(Routes.ACTIVITY) {
                        AllActivityScreen(vm, navController::popBackStack) {
                            safeNavigate(Routes.customer(it))
                        }
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
                            onBack = {
                                FormDraftStore.clearActiveDraft(context)
                                navController.popBackStack()
                            },
                            onSaved = { id ->
                                FormDraftStore.clearActiveDraft(context)
                                navController.navigate(Routes.customer(id)) {
                                    popUpTo(Routes.ADD_CUSTOMER) { inclusive = true }
                                }
                            }
                        )
                    }
                    composable(
                        Routes.CUSTOMER,
                        arguments = listOf(
                            navArgument("customerId") { type = NavType.StringType },
                            navArgument("editPhone") {
                                type = NavType.BoolType
                                defaultValue = false
                            }
                        )
                    ) {
                        val id = it.arguments?.getString("customerId").orEmpty()
                        CustomerProfileScreenV6(
                            vm = vm,
                            customerId = id,
                            openPhoneEditor = it.arguments?.getBoolean("editPhone") == true,
                            onBack = ::backOrHome,
                            onDeleted = {
                                // The customer list might not exist below this profile (e.g. search).
                                // Build a fresh HOME → CUSTOMERS stack after successful deletion.
                                navigateHome()
                                navController.navigate(Routes.CUSTOMERS) { launchSingleTop = true }
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
                        AddDebtScreenV12(vm, id) {
                            FormDraftStore.clearActiveDraft(context)
                            navController.popBackStack()
                        }
                    }
                    composable(
                        Routes.ADD_PAYMENT,
                        arguments = listOf(navArgument("customerId") { type = NavType.StringType })
                    ) {
                        val id = it.arguments?.getString("customerId").orEmpty()
                        AddPaymentScreenV12(vm, id) {
                            FormDraftStore.clearActiveDraft(context)
                            navController.popBackStack()
                        }
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
                        AreasScreenV4(vm, navController::popBackStack, onArea = { navController.navigate(Routes.areaCustomers(it)) })
                    }
                    composable(
                        Routes.AREA_CUSTOMERS,
                        arguments = listOf(navArgument("areaName") { type = NavType.StringType })
                    ) {
                        val name = it.arguments?.getString("areaName").orEmpty()
                        AreaCustomersScreenV4(vm, name, navController::popBackStack,
                            onCustomer = { id -> navController.navigate(Routes.customer(id)) })
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
                    composable(Routes.SHOPPING) { MedicineShoppingScreen(::backOrHome,shoppingShareId,onShoppingShareHandled) }
                    composable(Routes.MONTHLY_REPORTS) { MonthlyReportsScreen(vm,::backOrHome) }
                    composable(Routes.REPORTS) {
                        ReportsScreenV11(vm, onCustomer = { navController.navigate(Routes.customer(it)) }, onMonthly = { safeNavigate(Routes.MONTHLY_REPORTS) })
                    }
                    composable(
                        "${Routes.FOLLOWUP}?showAll={showAll}",
                        arguments = listOf(navArgument("showAll") {
                            type = NavType.BoolType
                            defaultValue = false
                        })
                    ) { destination ->
                        SmartDebtFollowupV316(
                            vm,
                            navController::popBackStack,
                            onCustomer = { navController.navigate(Routes.customer(it)) },
                            initialShowAll = destination.arguments?.getBoolean("showAll") == true
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreenV10(
                            vm = vm,
                            onCustomer = { id ->
                                navController.navigate(Routes.customer(id)) { launchSingleTop = true }
                            },
                            onAddPhone = { id ->
                                navController.navigate(Routes.customer(id, editPhone = true))
                            }
                        )
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

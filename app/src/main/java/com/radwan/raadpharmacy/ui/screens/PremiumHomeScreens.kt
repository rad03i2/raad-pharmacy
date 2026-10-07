package com.radwan.raadpharmacy.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.cloud.CloudSyncRuntime
import com.radwan.raadpharmacy.cloud.CloudUiEvent
import com.radwan.raadpharmacy.cloud.CloudUiEvents
import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.ui.components.CustomerAvatar
import com.radwan.raadpharmacy.ui.components.CustomerCard
import com.radwan.raadpharmacy.ui.components.QuickActionCard
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.components.SectionTitle
import com.radwan.raadpharmacy.ui.components.StatusChip
import com.radwan.raadpharmacy.ui.components.TransactionRow
import com.radwan.raadpharmacy.ui.theme.DebtRed
import com.radwan.raadpharmacy.ui.theme.MedicalBlueDark
import com.radwan.raadpharmacy.ui.theme.PaidGreen
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatMoney
import com.radwan.raadpharmacy.util.normalizeIraqPhone
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenV3(
    vm: PharmacyLedgerViewModel,
    onCustomers: () -> Unit,
    onActivity: () -> Unit,
    onAddCustomer: () -> Unit,
    onSearch: () -> Unit,
    onQuickDebt: () -> Unit,
    onQuickPayment: () -> Unit,
    onCollections: () -> Unit,
    onDailyDebts: () -> Unit,
    onTopDebtors: () -> Unit,
    onAreas: () -> Unit,
    onFollowUp: () -> Unit,
    onCustomer: (String) -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val security by vm.securityState.collectAsStateWithLifecycle()
    val today = remember {
        val day = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE", Locale("ar", "IQ")))
        day + " • " + formatDate(System.currentTimeMillis())
    }
    val topDebtors = remember(customers, entries) { vm.topDebtors().take(3) }
    val recent = remember(entries) { entries.sortedByDescending { it.createdAt }.take(4) }
    val customerById = remember(customers) { customers.associateBy { it.id } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cloudRefreshing by remember { mutableStateOf(false) }
    var totalVisible by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) totalVisible = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PullToRefreshBox(
        isRefreshing = cloudRefreshing,
        onRefresh = {
            if (!cloudRefreshing) {
                cloudRefreshing = true
                scope.launch {
                    val result = CloudSyncRuntime.refreshNow(context)
                    cloudRefreshing = false

                    CloudUiEvents.emit(
                        if (result.isSuccess) {
                            CloudUiEvent(
                                title = "تم التحديث",
                                message = "تم جلب أحدث العمليات من السحابة.",
                                kind = CloudUiEvent.Kind.REFRESH
                            )
                        } else {
                            CloudUiEvent(
                                title = "تعذر التحديث",
                                message = "تحقق من الإنترنت ثم اسحب للأسفل مرة أخرى.",
                                kind = CloudUiEvent.Kind.INFO
                            )
                        }
                    )
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { V3Header(today, vm, onActivity) }
        item {
            V3DebtHero(
                totalDebt = vm.totalDebt(),
                debtors = vm.indebtedCustomersCount(),
                collections = vm.todayCollections(),
                hideAmounts = security.hideAmounts,
                totalVisible = totalVisible,
                onToggleTotal = { totalVisible = !totalVisible },
                onDebt = onQuickDebt,
                onPayment = onQuickPayment
            )
        }
        item { V3Search(onSearch) }
        item { SectionTitle("اختصارات الجولة") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickActionCard("زبون جديد", Icons.Rounded.PersonAdd, onAddCustomer, Modifier.weight(1f))
                QuickActionCard("المناطق", Icons.Rounded.LocationOn, onAreas, Modifier.weight(1f))
                QuickActionCard("المتابعة", Icons.Rounded.Assessment, onFollowUp, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                V3Stat(
                    "ديون اليوم",
                    if (security.hideAmounts) "•••• د.ع" else formatMoney(vm.todayDebts()),
                    Icons.Rounded.ReceiptLong,
                    onDailyDebts,
                    Modifier.weight(1f),
                    false
                )
                V3Stat(
                    "تحصيل اليوم",
                    if (security.hideAmounts) "•••• د.ع" else formatMoney(vm.todayCollections()),
                    Icons.Rounded.TrendingUp,
                    onCollections,
                    Modifier.weight(1f),
                    true
                )
            }
        }
        item {
            V3Stat(
                "كل الزبائن",
                customers.size.toString() + " زبون",
                Icons.Rounded.People,
                onCustomers,
                Modifier.fillMaxWidth(),
                false,
                vm.indebtedCustomersCount().toString() + " حساب مفتوح"
            )
        }
        item { SectionTitle("أعلى المديونيات", "عرض الكل", onTopDebtors) }
        if (topDebtors.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "لا توجد حسابات مفتوحة حاليًا.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        } else {
            items(topDebtors, key = { it.id }) { customer ->
                CustomerCard(
                    customer,
                    vm.balance(customer),
                    { onCustomer(customer.id) },
                    hideBalance = security.hideAmounts
                )
            }
        }
        item { SectionTitle("آخر الحركات", "التحصيلات", onCollections) }
        items(recent, key = { it.id }) { entry ->
            val customer = customerById[entry.customerId]
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { customer?.let { onCustomer(it.id) } },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (customer != null) {
                            CustomerAvatar(
                                customerId = customer.id,
                                size = 34.dp
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f).padding(horizontal = 9.dp)
                        ) {
                            Text(
                                customer?.name ?: "زبون",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                customer?.area.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TransactionRow(entry, hideAmounts = security.hideAmounts)
                }
            }
        }
    }
    }
}

@Composable
private fun V3Header(
    today: String,
    vm: PharmacyLedgerViewModel,
    onActivity: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("دفتر صيدلية رعد", style = MaterialTheme.typography.headlineMedium)
            Text(today, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HomeVoiceLedgerActionV211(vm)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp).clickable(onClick = onActivity)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Wallet,
                        contentDescription = "سجل جميع الحركات",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun V3DebtHero(
    totalDebt: Long,
    debtors: Int,
    collections: Long,
    hideAmounts: Boolean,
    totalVisible: Boolean,
    onToggleTotal: () -> Unit,
    onDebt: () -> Unit,
    onPayment: () -> Unit
) {
    val brush = Brush.linearGradient(listOf(MedicalBlueDark, MaterialTheme.colorScheme.primary))
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(brush, MaterialTheme.shapes.extraLarge)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("إجمالي الدين الحالي", color = Color.White.copy(alpha = 0.78f), modifier = Modifier.weight(1f))
                IconButton(onClick = onToggleTotal, enabled = !hideAmounts) {
                    Icon(if (totalVisible && !hideAmounts) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = if (totalVisible) "إخفاء إجمالي الديون" else "إظهار إجمالي الديون",
                        tint = Color.White)
                }
            }
            Text(
                if (hideAmounts || !totalVisible) "•••• د.ع" else formatMoney(totalDebt),
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                debtors.toString() + " حساب مفتوح • تحصيل اليوم " +
                    if (hideAmounts) "•••• د.ع" else formatMoney(collections),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            V3HeroAction("إضافة دين", Icons.Rounded.Add, onDebt, false, Modifier.weight(1f))
            V3HeroAction("تسجيل تحصيل", Icons.Rounded.Wallet, onPayment, true, Modifier.weight(1f))
        }
    }
}

@Composable
private fun V3HeroAction(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    filled: Boolean,
    modifier: Modifier
) {
    Surface(
        modifier = modifier.height(52.dp).clickable(onClick = onClick),
        shape = CircleShape,
        color = if (filled) Color.White else Color.White.copy(alpha = 0.12f),
        border = if (filled) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                null,
                tint = if (filled) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.size(18.dp)
            )
            Text(
                title,
                modifier = Modifier.padding(start = 6.dp),
                color = if (filled) MaterialTheme.colorScheme.primary else Color.White,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun V3Search(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(
                "ابحث عن زبون بالاسم أو الهاتف",
                modifier = Modifier.padding(horizontal = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun V3Stat(
    label: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    emphasis: Boolean,
    supporting: String? = null
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (emphasis) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (emphasis) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    label,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!supporting.isNullOrBlank()) {
                Text(supporting, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private enum class CustomerSortV3(val label: String) {
    HIGHEST("الأعلى دينًا"),
    NAME("الاسم"),
    LATEST("آخر تعامل"),
    AREA("المنطقة")
}

@Composable
fun CustomersScreenV3(
    vm: PharmacyLedgerViewModel,
    onAdd: () -> Unit,
    onCustomer: (String) -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val security by vm.securityState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(CustomerSortV3.HIGHEST) }

    val visible = remember(customers, entries, query, sort) {
        val filtered = customers.filter {
            query.isBlank() ||
                it.name.contains(query, true) ||
                it.phone.orEmpty().contains(query) ||
                it.area.contains(query, true)
        }
        when (sort) {
            CustomerSortV3.HIGHEST -> filtered.sortedByDescending { vm.balance(it) }
            CustomerSortV3.NAME -> filtered.sortedBy { it.name }
            CustomerSortV3.LATEST -> filtered.sortedByDescending { vm.lastEntryFor(it.id)?.createdAt ?: it.createdAt }
            CustomerSortV3.AREA -> filtered.sortedWith(compareBy<Customer> { it.area }.thenBy { it.name })
        }
    }

    Scaffold(
        topBar = { ScreenTopBar("الزبائن") },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Rounded.PersonAdd, null) },
                text = { Text("زبون جديد") },
                shape = CircleShape
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 94.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(customers.size.toString() + " زبون", style = MaterialTheme.typography.titleLarge)
                            Text(
                                vm.indebtedCustomersCount().toString() + " حساب مفتوح",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            if (security.hideAmounts) "•••• د.ع" else formatMoney(vm.totalDebt()),
                            style = MaterialTheme.typography.titleMedium,
                            color = DebtRed
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("بحث بالاسم، الهاتف أو المنطقة") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    shape = CircleShape
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CustomerSortV3.entries.forEach { option ->
                        FilterChip(
                            selected = sort == option,
                            onClick = { sort = option },
                            label = { Text(option.label) }
                        )
                    }
                }
            }
            item {
                Text(
                    "النتائج • " + visible.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(visible, key = { it.id }) { customer ->
                CustomerCard(
                    customer = customer,
                    balance = vm.balance(customer),
                    lastActivity = vm.lastEntryFor(customer.id)?.let { "آخر تعامل " + formatDate(it.createdAt) },
                    hideBalance = security.hideAmounts,
                    onClick = { onCustomer(customer.id) }
                )
            }
        }
    }
}

@Composable
fun AddCustomerScreenV3(
    vm: PharmacyLedgerViewModel,
    onBack: () -> Unit,
    onSaved: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var area by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var openingDebt by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    Scaffold(topBar = { ScreenTopBar("زبون جديد", onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("إضافة زبون", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "سجّل الأساسيات الآن، ويمكن إكمال التفاصيل لاحقًا.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            V3FormSection("المعلومات الأساسية") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; showError = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("اسم الزبون *") },
                    singleLine = true,
                    isError = showError && name.isBlank(),
                    supportingText = { if (showError && name.isBlank()) Text("اسم الزبون مطلوب") },
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("رقم الهاتف") },
                    placeholder = { Text("07XXXXXXXXX") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = area,
                    onValueChange = { area = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("المنطقة / الحي") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("وصف المكان") },
                    minLines = 2,
                    shape = MaterialTheme.shapes.medium
                )
            }

            V3FormSection("الحساب") {
                OutlinedTextField(
                    value = openingDebt,
                    onValueChange = { openingDebt = it.filter(Char::isDigit).take(12) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("الدين السابق") },
                    suffix = { Text("د.ع") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("ملاحظات") },
                    minLines = 2,
                    shape = MaterialTheme.shapes.medium
                )
            }

            Button(
                onClick = {
                    if (name.isBlank()) showError = true
                    else {
                        scope.launch {
                            val customer = vm.addCustomer(
                                name,
                                phone.takeIf { it.isNotBlank() },
                                area,
                                address,
                                openingDebt.toLongOrNull() ?: 0L,
                                notes
                            )
                            onSaved(customer.id)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("حفظ الزبون")
            }
        }
    }
}

@Composable
private fun V3FormSection(title: String, content: @Composable () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun CustomerProfileScreenV3(
    vm: PharmacyLedgerViewModel,
    customerId: String,
    onBack: () -> Unit,
    onAddDebt: () -> Unit,
    onPayment: () -> Unit,
    onTransactions: () -> Unit,
    onStatement: () -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val customer = customers.firstOrNull { it.id == customerId }
    val context = LocalContext.current

    if (customer == null) {
        Scaffold(topBar = { ScreenTopBar("الزبون", onBack) }) { p ->
            Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) {
                Text("تعذر العثور على الزبون")
            }
        }
        return
    }

    val balance = vm.balance(customer)
    val customerEntries = remember(entries, customerId) {
        entries.filter { it.customerId == customerId }.sortedByDescending { it.createdAt }
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                customer.name,
                onBack,
                actions = {
                    if (!customer.phone.isNullOrBlank()) {
                        IconButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + customer.phone)))
                        }) { Icon(Icons.Rounded.Call, "اتصال") }
                        IconButton(onClick = {
                            val phone = normalizeIraqPhone(customer.phone).removePrefix("+")
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + phone)))
                            }
                        }) { Icon(Icons.Rounded.Chat, "واتساب") }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { V3AccountHero(customer, balance) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    V3AccountAction("إضافة دين", Icons.Rounded.Add, onAddDebt, Modifier.weight(1f), false)
                    V3AccountAction("تحصيل", Icons.Rounded.Wallet, onPayment, Modifier.weight(1f), true, balance > 0)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickActionCard("كشف الحساب", Icons.Rounded.ReceiptLong, onStatement, Modifier.weight(1f))
                    QuickActionCard("كل الحركات", Icons.Rounded.Assessment, onTransactions, Modifier.weight(1f))
                }
            }
            item { SectionTitle("معلومات الزبون") }
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        V3ProfileInfo("المنطقة", customer.area.ifBlank { "غير محددة" })
                        V3ProfileInfo("الهاتف", customer.phone ?: "غير مضاف")
                        if (customer.address.isNotBlank()) V3ProfileInfo("المكان", customer.address)
                    }
                }
            }
            item { SectionTitle("آخر الحركات", "السجل الكامل", onTransactions) }
            items(customerEntries.take(5), key = { it.id }) { entry ->
                Surface(
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    TransactionRow(entry, modifier = Modifier.padding(horizontal = 14.dp))
                }
            }
        }
    }
}

@Composable
private fun V3AccountHero(customer: Customer, balance: Long) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (balance > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CustomerAvatar(
                    customerId = customer.id,
                    size = 50.dp
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(customer.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        customer.area.ifBlank { "بدون منطقة" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusChip(if (balance == 0L) "مسدد" else "مفتوح", balance == 0L)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("الدين الحالي", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    formatMoney(balance),
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (balance > 0) DebtRed else PaidGreen
                )
            }
        }
    }
}

@Composable
private fun V3AccountAction(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    primary: Boolean,
    enabled: Boolean = true
) {
    Surface(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (primary && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = if (primary && enabled) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 13.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                null,
                tint = if (primary && enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
            Text(
                title,
                modifier = Modifier.padding(start = 7.dp),
                color = if (primary && enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun V3ProfileInfo(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            modifier = Modifier.weight(1f).padding(start = 14.dp),
            textAlign = TextAlign.End,
            fontWeight = FontWeight.SemiBold
        )
    }
}

package com.radwan.raadpharmacy.ui.screens

import com.radwan.raadpharmacy.ui.components.rememberLedgerFlingBehavior

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.ui.components.CustomerCard
import com.radwan.raadpharmacy.ui.components.QuickActionCard
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.components.SectionTitle
import com.radwan.raadpharmacy.ui.components.StatusChip
import com.radwan.raadpharmacy.ui.components.TransactionRow
import com.radwan.raadpharmacy.ui.theme.DebtRed
import com.radwan.raadpharmacy.ui.theme.PaidGreen
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatMoney
import com.radwan.raadpharmacy.util.normalizeIraqPhone
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    vm: PharmacyLedgerViewModel,
    onCustomers: () -> Unit,
    onAddCustomer: () -> Unit,
    onSearch: () -> Unit,
    onCollections: () -> Unit,
    onDailyDebts: () -> Unit,
    onTopDebtors: () -> Unit,
    onAreas: () -> Unit,
    onFollowUp: () -> Unit,
    onCustomer: (String) -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val today = remember {
        val day = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE", Locale("ar", "IQ")))
        day + "، " + formatDate(System.currentTimeMillis())
    }
    val topDebtors = remember(customers, entries) { vm.topDebtors().take(3) }
    val recent = remember(entries) { entries.sortedByDescending { it.createdAt }.take(4) }

    LazyColumn(
            flingBehavior = rememberLedgerFlingBehavior(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("دفتر صيدلية رعد", style = MaterialTheme.typography.headlineMedium)
                Text(today, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            DebtHero(
                totalDebt = vm.totalDebt(),
                debtors = vm.indebtedCustomersCount(),
                collections = vm.todayCollections(),
                onDebt = onSearch,
                onPayment = onSearch
            )
        }

        item {
            HomeSearch(onClick = onSearch)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                QuickActionCard("زبون جديد", Icons.Rounded.PersonAdd, onAddCustomer, Modifier.weight(1f))
                QuickActionCard("المناطق", Icons.Rounded.LocationOn, onAreas, Modifier.weight(1f))
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MiniStat("ديون اليوم", formatMoney(vm.todayDebts()), onDailyDebts, Modifier.weight(1f))
                MiniStat("تحصيلات اليوم", formatMoney(vm.todayCollections()), onCollections, Modifier.weight(1f))
                MiniStat("الزبائن", customers.size.toString(), onCustomers, Modifier.weight(1f))
            }
        }

        item { SectionTitle("أعلى المديونيات", "عرض الكل", onTopDebtors) }
        items(topDebtors, key = { it.id }) { customer ->
            CustomerCard(
                customer = customer,
                balance = vm.balance(customer),
                onClick = { onCustomer(customer.id) }
            )
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onFollowUp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Assessment, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text("متابعة الحسابات", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "اعرف من يحتاج متابعة اليوم",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        item { SectionTitle("آخر الحركات", "كل التحصيلات", onCollections) }
        if (recent.isEmpty()) {
            item {
                Text("لا توجد حركات حتى الآن.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(recent, key = { it.id }) { entry ->
                val customer = customers.firstOrNull { it.id == entry.customerId }
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { customer?.let { onCustomer(it.id) } },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                        Text(
                            customer?.name ?: "زبون",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 11.dp)
                        )
                        TransactionRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun DebtHero(
    totalDebt: Long,
    debtors: Int,
    collections: Long,
    onDebt: () -> Unit,
    onPayment: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primary
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("إجمالي الدين الحالي", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f))
                Text(
                    formatMoney(totalDebt),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    debtors.toString() + " زبون بحساب مفتوح • تحصيل اليوم " + formatMoney(collections),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.76f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Surface(
                    modifier = Modifier.weight(1f).clickable(onClick = onDebt),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.13f)
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                        Text("إضافة دين", color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(start = 5.dp))
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f).clickable(onClick = onPayment),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onPrimary
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Wallet, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text("تحصيل", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 5.dp), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSearch(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(
                "ابحث عن زبون بالاسم أو الهاتف",
                modifier = Modifier.padding(horizontal = 9.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun MiniStat(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier.clickable(onClick = onClick),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private enum class CustomerSort(val label: String) {
    HIGHEST("الأعلى دينًا"),
    NAME("الاسم"),
    LATEST("آخر تعامل"),
    AREA("المنطقة")
}

@Composable
fun CustomersScreen(
    vm: PharmacyLedgerViewModel,
    onAdd: () -> Unit,
    onCustomer: (String) -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(CustomerSort.HIGHEST) }

    val visible = remember(customers, entries, query, sort) {
        val filtered = customers.filter {
            query.isBlank() ||
                it.name.contains(query, true) ||
                it.phone.orEmpty().contains(query) ||
                it.area.contains(query, true)
        }
        when (sort) {
            CustomerSort.HIGHEST -> filtered.sortedByDescending { vm.balance(it) }
            CustomerSort.NAME -> filtered.sortedBy { it.name }
            CustomerSort.LATEST -> filtered.sortedByDescending { vm.lastEntryFor(it.id)?.createdAt ?: it.createdAt }
            CustomerSort.AREA -> filtered.sortedWith(compareBy<Customer> { it.area }.thenBy { it.name })
        }
    }

    Scaffold(
        topBar = { ScreenTopBar("الزبائن") },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Rounded.PersonAdd, null) },
                text = { Text("زبون جديد") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { padding ->
        LazyColumn(
            flingBehavior = rememberLedgerFlingBehavior(),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("بحث سريع") },
                    placeholder = { Text("الاسم، الهاتف، المنطقة") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    shape = CircleShape
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CustomerSort.entries.forEach { option ->
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
                    visible.size.toString() + " زبون",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(visible, key = { it.id }) { customer ->
                CustomerCard(
                    customer = customer,
                    balance = vm.balance(customer),
                    lastActivity = vm.lastEntryFor(customer.id)?.let { "آخر تعامل " + formatDate(it.createdAt) },
                    onClick = { onCustomer(customer.id) }
                )
            }
        }
    }
}

@Composable
fun AddCustomerScreen(
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
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState(), flingBehavior = rememberLedgerFlingBehavior()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Text("أدخل المعلومات الأساسية فقط، ويمكن إكمال الباقي لاحقًا.", color = MaterialTheme.colorScheme.onSurfaceVariant)

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

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    if (name.isBlank()) showError = true
                    else {
                        scope.launch {
                            val customer = vm.addCustomer(
                                name = name,
                                phone = phone.takeIf { it.isNotBlank() },
                                area = area,
                                address = address,
                                openingDebt = openingDebt.toLongOrNull() ?: 0L,
                                notes = notes
                            )
                            onSaved(customer.id)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("حفظ الزبون")
            }
        }
    }
}

@Composable
fun CustomerProfileScreen(
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
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + phone))) }
                        }) { Icon(Icons.Rounded.Chat, "واتساب") }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            flingBehavior = rememberLedgerFlingBehavior(),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (balance > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("الدين الحالي", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            formatMoney(balance),
                            style = MaterialTheme.typography.headlineLarge,
                            color = if (balance > 0) DebtRed else PaidGreen
                        )
                        StatusChip(if (balance == 0L) "الحساب مسدد" else "حساب مفتوح", balance == 0L)
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Button(onClick = onAddDebt, modifier = Modifier.weight(1f).height(50.dp)) { Text("إضافة دين") }
                    Button(
                        onClick = onPayment,
                        enabled = balance > 0,
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) { Text("تحصيل") }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    QuickActionCard("كشف الحساب", Icons.Rounded.ReceiptLong, onStatement, Modifier.weight(1f))
                    QuickActionCard("كل الحركات", Icons.Rounded.Assessment, onTransactions, Modifier.weight(1f))
                }
            }

            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProfileInfo("المنطقة", customer.area.ifBlank { "غير محددة" })
                        ProfileInfo("الهاتف", customer.phone ?: "غير مضاف")
                        if (customer.address.isNotBlank()) ProfileInfo("المكان", customer.address)
                    }
                }
            }

            item { SectionTitle("آخر الحركات", "السجل الكامل", onTransactions) }
            if (customerEntries.isEmpty()) {
                item { Text("لا توجد حركات مالية.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(customerEntries.take(5), key = { it.id }) { entry ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        TransactionRow(entry, modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileInfo(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 14.dp)
        )
    }
}

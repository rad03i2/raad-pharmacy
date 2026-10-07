package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.data.LedgerEntry
import com.radwan.raadpharmacy.ui.components.CustomerAvatar
import com.radwan.raadpharmacy.ui.components.CustomerCard
import com.radwan.raadpharmacy.ui.components.EmptyState
import com.radwan.raadpharmacy.ui.components.MetricCard
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.components.SectionTitle
import com.radwan.raadpharmacy.ui.theme.DebtRed
import com.radwan.raadpharmacy.ui.theme.PaidGreen
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatMoney
import com.radwan.raadpharmacy.util.formatTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private fun dayOfV4(timestamp: Long): LocalDate =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyCollectionsScreenV4(vm: PharmacyLedgerViewModel, onCustomer: (String) -> Unit) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var picker by remember { mutableStateOf(false) }
    val byId = remember(customers) { customers.associateBy { it.id } }
    val payments = remember(entries, selectedDate) {
        entries.filter { it.type == EntryType.PAYMENT && dayOfV4(it.createdAt) == selectedDate }
            .sortedByDescending { it.createdAt }
    }
    val total = payments.sumOf { it.amount }
    val customersCount = payments.map { it.customerId }.toSet().size
    val largest = payments.maxOfOrNull { it.amount } ?: 0L

    if (picker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { picker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        selectedDate = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    picker = false
                }) { Text("اختيار") }
            },
            dismissButton = { TextButton(onClick = { picker = false }) { Text("إلغاء") } }
        ) { DatePicker(state = state) }
    }

    Scaffold(topBar = { ScreenTopBar("التحصيلات") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedButton(
                    onClick = { picker = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Rounded.CalendarMonth, null)
                    Text(
                        if (selectedDate == LocalDate.now()) "اليوم • " + formatDate(System.currentTimeMillis())
                        else selectedDate.toString(),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
            item {
                V4OperationHero(
                    "إجمالي التحصيلات",
                    total,
                    payments.size.toString() + " عملية • " + customersCount + " زبون",
                    true
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("عدد العمليات", payments.size.toString(), Icons.Rounded.ReceiptLong, Modifier.weight(1f))
                    MetricCard("أكبر تحصيل", formatMoney(largest), Icons.Rounded.TrendingUp, Modifier.weight(1f))
                }
            }
            item { SectionTitle("تفاصيل التحصيلات") }
            if (payments.isEmpty()) {
                item { EmptyState("لا توجد تحصيلات", "لا توجد عمليات تحصيل في هذا التاريخ.") }
            } else {
                items(payments, key = { it.id }) { entry ->
                    V4OperationRow(entry, byId[entry.customerId], true) {
                        byId[entry.customerId]?.let { onCustomer(it.id) }
                    }
                }
            }
        }
    }
}

@Composable
fun DailyDebtsScreenV4(vm: PharmacyLedgerViewModel, onBack: () -> Unit, onCustomer: (String) -> Unit) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val byId = remember(customers) { customers.associateBy { it.id } }
    val debts = remember(entries, today) {
        entries.filter { it.type == EntryType.DEBT && dayOfV4(it.createdAt) == today }
            .sortedByDescending { it.createdAt }
    }
    val total = debts.sumOf { it.amount }
    val bottles = debts.sumOf { it.bottles ?: 0 }
    val unique = debts.map { it.customerId }.toSet().size
    val avg = if (debts.isEmpty()) 0L else total / debts.size

    Scaffold(topBar = { ScreenTopBar("ديون اليوم", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { V4OperationHero("إجمالي ديون اليوم", total, unique.toString() + " زبون • " + debts.size + " عملية", false) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("عدد العمليات", debts.size.toString(), Icons.Rounded.ReceiptLong, Modifier.weight(1f))
                    MetricCard("متوسط العملية", formatMoney(avg), Icons.Rounded.TrendingUp, Modifier.weight(1f))
                }
            }
            item { SectionTitle("تفاصيل الديون") }
            if (debts.isEmpty()) {
                item { EmptyState("لا توجد ديون اليوم", "عمليات البيع بالدين ستظهر هنا.") }
            } else {
                items(debts, key = { it.id }) { entry ->
                    V4OperationRow(entry, byId[entry.customerId], false) {
                        byId[entry.customerId]?.let { onCustomer(it.id) }
                    }
                }
            }
        }
    }
}

@Composable
fun TopDebtorsScreenV4(vm: PharmacyLedgerViewModel, onBack: () -> Unit, onCustomer: (String) -> Unit) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    var area by remember { mutableStateOf("الكل") }
    val areas = remember(customers) { listOf("الكل") + customers.map { it.area }.filter { it.isNotBlank() }.distinct().sorted() }
    val debtors = remember(customers, entries, area) {
        vm.topDebtors().filter { area == "الكل" || it.area == area }
    }
    val max = debtors.maxOfOrNull { vm.balance(it) }?.coerceAtLeast(1L) ?: 1L

    Scaffold(topBar = { ScreenTopBar("أعلى المديونيات", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("إجمالي الحسابات المفتوحة", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatMoney(vm.totalDebt()), style = MaterialTheme.typography.headlineMedium, color = DebtRed)
                        Text(vm.indebtedCustomersCount().toString() + " زبون عليه دين", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    areas.forEach { item ->
                        FilterChip(selected = area == item, onClick = { area = item }, label = { Text(item) })
                    }
                }
            }
            if (debtors.isEmpty()) {
                item { EmptyState("لا توجد مديونيات", "لا توجد حسابات مفتوحة ضمن هذا التصنيف.") }
            } else {
                itemsIndexed(debtors, key = { _, c -> c.id }) { index, customer ->
                    val balance = vm.balance(customer)
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onCustomer(customer.id) },
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CustomerAvatar(
                                    customerId = customer.id,
                                    size = 40.dp
                                )
                                Text(
                                    "#" + (index + 1).toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 7.dp)
                                )
                                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                                    Text(customer.name, style = MaterialTheme.typography.titleMedium)
                                    Text(customer.area.ifBlank { "بدون منطقة" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(formatMoney(balance), color = DebtRed, style = MaterialTheme.typography.titleMedium)
                            }
                            LinearProgressIndicator(
                                progress = { balance.toFloat() / max.toFloat() },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = DebtRed,
                                trackColor = MaterialTheme.colorScheme.errorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AreasScreenV4(vm: PharmacyLedgerViewModel, onBack: () -> Unit, onCustomer: (String) -> Unit) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf<String?>(null) }
    val today = remember { LocalDate.now() }
    val todayPaid = remember(entries) {
        entries.filter { it.type == EntryType.PAYMENT && dayOfV4(it.createdAt) == today }
            .groupBy { it.customerId }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
    }
    val groups = remember(customers, entries) {
        customers.groupBy { it.area.ifBlank { "غير محددة" } }
            .map { (name, list) ->
                Triple(name, list.sortedByDescending { vm.balance(it) }, list.sumOf { vm.balance(it) })
            }.sortedByDescending { it.third }
    }

    Scaffold(topBar = { ScreenTopBar("المناطق", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Column(Modifier.padding(horizontal = 11.dp)) {
                            Text(groups.size.toString() + " منطقة", style = MaterialTheme.typography.titleLarge)
                            Text("اختر منطقة لعرض الزبائن والحسابات", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            groups.forEach { (name, list, debt) ->
                val collected = list.sumOf { todayPaid[it.id] ?: 0L }
                item(key = "a-" + name) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { expanded = if (expanded == name) null else name },
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.titleMedium)
                                    Text(list.size.toString() + " زبون", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(formatMoney(debt), color = DebtRed, style = MaterialTheme.typography.titleMedium)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("تحصيل اليوم " + formatMoney(collected), style = MaterialTheme.typography.labelMedium, color = PaidGreen)
                                Text(if (expanded == name) "إخفاء" else "عرض الزبائن", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                if (expanded == name) {
                    items(list, key = { "c-" + it.id }) { customer ->
                        CustomerCard(customer, vm.balance(customer), { onCustomer(customer.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun V4OperationHero(label: String, amount: Long, supporting: String, positive: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (positive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    ) {
        Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatMoney(amount), style = MaterialTheme.typography.headlineLarge, color = if (positive) PaidGreen else DebtRed)
            Text(supporting, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun V4OperationRow(entry: LedgerEntry, customer: Customer?, positive: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (customer != null) {
                CustomerAvatar(
                    customerId = customer.id,
                    size = 40.dp
                )
            } else {
                Surface(
                    shape = CircleShape,
                    color = if (positive) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.ReceiptLong,
                            null,
                            tint = if (positive) PaidGreen else DebtRed,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(customer?.name ?: "زبون", style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(customer?.area?.takeIf { it.isNotBlank() }, entry.bottles?.let { it.toString() + " عنصر" }, formatTime(entry.createdAt)).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(formatMoney(entry.amount), color = if (positive) PaidGreen else DebtRed, style = MaterialTheme.typography.titleMedium)
        }
    }
}

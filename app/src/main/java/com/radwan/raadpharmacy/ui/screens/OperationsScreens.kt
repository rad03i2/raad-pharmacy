package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.LocalShipping
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.data.LedgerEntry
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

private fun dayOf(timestamp: Long): LocalDate =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

private data class AreaSummary(
    val name: String,
    val customers: List<Customer>,
    val debt: Long,
    val todayCollections: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyCollectionsScreen(
    vm: PharmacyLedgerViewModel,
    onCustomer: (String) -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var showPicker by remember { mutableStateOf(false) }

    val customerById = remember(customers) { customers.associateBy { it.id } }
    val payments = remember(entries, selectedDate) {
        entries.asSequence()
            .filter { it.type == EntryType.PAYMENT && dayOf(it.createdAt) == selectedDate }
            .sortedByDescending { it.createdAt }
            .toList()
    }
    val total = remember(payments) { payments.sumOf { it.amount } }
    val uniqueCustomers = remember(payments) { payments.map { it.customerId }.toSet().size }
    val largest = remember(payments) { payments.maxOfOrNull { it.amount } ?: 0L }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        selectedDate = Instant.ofEpochMilli(it)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                    }
                    showPicker = false
                }) { Text("اختيار") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("إلغاء") } }
        ) {
            DatePicker(state = pickerState)
        }
    }

    Scaffold(topBar = { ScreenTopBar("التحصيلات اليومية") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            item {
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Rounded.CalendarMonth, null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(
                        if (selectedDate == LocalDate.now()) "اليوم • " + formatDate(System.currentTimeMillis())
                        else selectedDate.toString()
                    )
                }
            }

            item {
                SummaryHero(
                    label = "إجمالي التحصيلات",
                    value = total,
                    supporting = payments.size.toString() + " عملية • " + uniqueCustomers.toString() + " زبون",
                    positive = true
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    MetricCard("عدد العمليات", payments.size.toString(), Icons.Rounded.ReceiptLong, Modifier.weight(1f))
                    MetricCard("أكبر تحصيل", formatMoney(largest), Icons.Rounded.TrendingUp, Modifier.weight(1f))
                }
            }

            item { SectionTitle("العمليات") }

            if (payments.isEmpty()) {
                item { EmptyState("لا توجد تحصيلات", "لا توجد عمليات تحصيل في هذا التاريخ.") }
            } else {
                items(payments, key = { it.id }) { entry ->
                    val customer = customerById[entry.customerId]
                    OperationCard(
                        entry = entry,
                        customer = customer,
                        onClick = { customer?.let { onCustomer(it.id) } },
                        positive = true
                    )
                }
            }
        }
    }
}

@Composable
fun DailyDebtsScreen(
    vm: PharmacyLedgerViewModel,
    onBack: () -> Unit,
    onCustomer: (String) -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val customerById = remember(customers) { customers.associateBy { it.id } }

    val debts = remember(entries, today) {
        entries.asSequence()
            .filter { it.type == EntryType.DEBT && dayOf(it.createdAt) == today }
            .sortedByDescending { it.createdAt }
            .toList()
    }
    val total = remember(debts) { debts.sumOf { it.amount } }
    val bottles = remember(debts) { debts.sumOf { it.bottles ?: 0 } }
    val customerCount = remember(debts) { debts.map { it.customerId }.toSet().size }
    val average = remember(debts, total) { if (debts.isEmpty()) 0L else total / debts.size }

    Scaffold(topBar = { ScreenTopBar("ديون اليوم", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            item {
                SummaryHero(
                    label = "إجمالي ديون اليوم",
                    value = total,
                    supporting = customerCount.toString() + " زبون • " + bottles.toString() + " عنصر",
                    positive = false
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    MetricCard("عدد العمليات", debts.size.toString(), Icons.Rounded.ReceiptLong, Modifier.weight(1f))
                    MetricCard("متوسط العملية", formatMoney(average), Icons.Rounded.TrendingUp, Modifier.weight(1f))
                }
            }

            item { SectionTitle("العمليات") }

            if (debts.isEmpty()) {
                item { EmptyState("لا توجد ديون اليوم", "عمليات البيع بالدين ستظهر هنا عند تسجيلها.") }
            } else {
                items(debts, key = { it.id }) { entry ->
                    val customer = customerById[entry.customerId]
                    OperationCard(
                        entry = entry,
                        customer = customer,
                        onClick = { customer?.let { onCustomer(it.id) } },
                        positive = false
                    )
                }
            }
        }
    }
}

@Composable
fun TopDebtorsScreen(
    vm: PharmacyLedgerViewModel,
    onBack: () -> Unit,
    onCustomer: (String) -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    var selectedArea by remember { mutableStateOf("الكل") }

    val areas = remember(customers) {
        listOf("الكل") + customers.map { it.area }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val debtors = remember(customers, entries, selectedArea) {
        vm.topDebtors().filter { selectedArea == "الكل" || it.area == selectedArea }
    }
    val maxDebt = remember(debtors) {
        debtors.maxOfOrNull { vm.balance(it) }?.coerceAtLeast(1L) ?: 1L
    }

    Scaffold(topBar = { ScreenTopBar("أعلى المديونيات", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    areas.forEach { area ->
                        FilterChip(
                            selected = selectedArea == area,
                            onClick = { selectedArea = area },
                            label = { Text(area) }
                        )
                    }
                }
            }

            if (debtors.isEmpty()) {
                item { EmptyState("لا توجد حسابات مفتوحة", "لا يوجد زبائن عليهم دين في هذا التصنيف.") }
            } else {
                itemsIndexed(debtors, key = { _, customer -> customer.id }) { index, customer ->
                    val balance = vm.balance(customer)
                    val lastPayment = vm.lastPaymentFor(customer.id)

                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onCustomer(customer.id) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    (index + 1).toString(),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(customer.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        customer.area.ifBlank { "بدون منطقة" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(formatMoney(balance), color = DebtRed, style = MaterialTheme.typography.titleMedium)
                            }

                            LinearProgressIndicator(
                                progress = { balance.toFloat() / maxDebt.toFloat() },
                                modifier = Modifier.fillMaxWidth().height(5.dp),
                                color = DebtRed,
                                trackColor = MaterialTheme.colorScheme.errorContainer
                            )

                            Text(
                                "آخر تحصيل: " +
                                    (lastPayment?.let { formatDate(it.createdAt) + " • " + formatMoney(it.amount) } ?: "لا يوجد"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AreasScreen(
    vm: PharmacyLedgerViewModel,
    onBack: () -> Unit,
    onCustomer: (String) -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    var expandedArea by remember { mutableStateOf<String?>(null) }

    val today = remember { LocalDate.now() }
    val todayCollectionsByCustomer = remember(entries, today) {
        entries.asSequence()
            .filter { it.type == EntryType.PAYMENT && dayOf(it.createdAt) == today }
            .groupBy { it.customerId }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
    }

    val summaries = remember(customers, entries, todayCollectionsByCustomer) {
        customers.groupBy { it.area.ifBlank { "غير محددة" } }
            .map { (area, list) ->
                AreaSummary(
                    name = area,
                    customers = list.sortedByDescending { vm.balance(it) },
                    debt = list.sumOf { vm.balance(it) },
                    todayCollections = list.sumOf { todayCollectionsByCustomer[it.id] ?: 0L }
                )
            }
            .sortedByDescending { it.debt }
    }

    Scaffold(topBar = { ScreenTopBar("المناطق / الأحياء", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            item {
                Text(
                    "رتّب الجولة حسب المنطقة، الدين الحالي، والتحصيلات.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            summaries.forEach { summary ->
                item(key = "area-" + summary.name) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable {
                            expandedArea = if (expandedArea == summary.name) null else summary.name
                        },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(15.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(summary.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        summary.customers.size.toString() + " زبون",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(formatMoney(summary.debt), color = DebtRed, style = MaterialTheme.typography.titleMedium)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "تحصيل اليوم " + formatMoney(summary.todayCollections),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PaidGreen
                                )
                                Text(
                                    if (expandedArea == summary.name) "إخفاء" else "عرض الزبائن",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                if (expandedArea == summary.name) {
                    items(summary.customers, key = { "area-customer-" + it.id }) { customer ->
                        CustomerCard(
                            customer = customer,
                            balance = vm.balance(customer),
                            onClick = { onCustomer(customer.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryHero(
    label: String,
    value: Long,
    supporting: String,
    positive: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (positive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(19.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                formatMoney(value),
                style = MaterialTheme.typography.headlineLarge,
                color = if (positive) PaidGreen else DebtRed
            )
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OperationCard(
    entry: LedgerEntry,
    customer: Customer?,
    onClick: () -> Unit,
    positive: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(customer?.name ?: "زبون", style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        customer?.area?.takeIf { it.isNotBlank() },
                        entry.bottles?.let { it.toString() + " عنصر" },
                        formatTime(entry.createdAt)
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                formatMoney(entry.amount),
                color = if (positive) PaidGreen else DebtRed,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

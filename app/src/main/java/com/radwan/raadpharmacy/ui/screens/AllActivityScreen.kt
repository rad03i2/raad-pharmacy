package com.radwan.raadpharmacy.ui.screens

import com.radwan.raadpharmacy.ui.components.rememberLedgerFlingBehavior

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.ui.components.EmptyState
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.theme.DebtRed
import com.radwan.raadpharmacy.ui.theme.PaidGreen
import com.radwan.raadpharmacy.util.ActivityPeriod
import com.radwan.raadpharmacy.util.filterActivity
import com.radwan.raadpharmacy.util.filterActivityOnDate
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatMoney
import com.radwan.raadpharmacy.util.formatTime
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllActivityScreen(vm: PharmacyLedgerViewModel, onBack: () -> Unit, onCustomer: (String) -> Unit) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val security by vm.securityState.collectAsStateWithLifecycle()
    var periodName by rememberSaveable { mutableStateOf(ActivityPeriod.DAY.name) }
    var selectedDayText by rememberSaveable { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            today = LocalDate.now()
            delay(60_000L)
        }
    }
    val period = ActivityPeriod.valueOf(periodName)
    val selectedDay = selectedDayText?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val movements = remember(entries, period, today, selectedDay) {
        if (selectedDay != null) filterActivityOnDate(entries, selectedDay)
        else filterActivity(entries, period, today)
    }
    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (selectedDay ?: today)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        selectedDayText = java.time.Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate().toString()
                    }
                    showDatePicker = false
                }) { Text("عرض الحركات") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("إلغاء") } }
        ) { DatePicker(state = pickerState) }
    }
    val byId = remember(customers) { customers.associateBy { it.id } }
    val debt = movements.filter { it.type == EntryType.DEBT }.sumOf { it.amount }
    val paid = movements.filter { it.type == EntryType.PAYMENT }.sumOf { it.amount }
    val groups = remember(movements) { movements.groupBy { formatDate(it.createdAt) } }
    fun money(amount: Long) = if (security.hideAmounts) "•••• د.ع" else formatMoney(amount)

    Scaffold(topBar = { ScreenTopBar("سجل جميع الحركات", onBack) }) { padding ->
        LazyColumn(
            flingBehavior = rememberLedgerFlingBehavior(),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedDay != null,
                        onClick = { showDatePicker = true },
                        label = {
                            Text(selectedDay?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US))
                                ?.let { "تاريخ محدد: $it" } ?: "اختيار يوم")
                        }
                    )
                    ActivityPeriod.entries.forEach { item ->
                        FilterChip(selected = selectedDay == null && period == item, onClick = {
                            periodName = item.name
                            selectedDayText = null
                        },
                            label = { Text(item.title) })
                    }
                }
            }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${movements.size} حركة", style = MaterialTheme.typography.titleLarge)
                        Text("الديون: " + money(debt), color = DebtRed)
                        Text("التحصيلات: " + money(paid), color = PaidGreen)
                    }
                }
            }
            if (movements.isEmpty()) {
                item { EmptyState("لا توجد حركات", "لا توجد ديون أو تحصيلات خلال الفترة المختارة.") }
            }
            groups.forEach { (date, rows) ->
                item(key = "date-$date") {
                    Text(date, style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp))
                }
                items(rows, key = { it.id }) { entry ->
                    val customer = byId[entry.customerId]
                    val payment = entry.type == EntryType.PAYMENT
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = customer != null) {
                            customer?.let { onCustomer(it.id) }
                        },
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(customer?.name ?: "زبون", style = MaterialTheme.typography.titleMedium)
                                Text((if (payment) "تحصيل" else "دين") + " • " + formatTime(entry.createdAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (entry.details.isNotBlank()) Text(entry.details,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Text(money(entry.amount), color = if (payment) PaidGreen else DebtRed,
                                style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

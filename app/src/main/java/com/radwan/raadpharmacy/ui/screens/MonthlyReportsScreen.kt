package com.radwan.raadpharmacy.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.MonthlyReports
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.util.formatMoney
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MonthlyReportsScreen(vm: PharmacyLedgerViewModel) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val security by vm.securityState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val current = YearMonth.now(MonthlyReports.zone)
    var selected by rememberSaveable { mutableStateOf(current.toString()) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var from by rememberSaveable { mutableStateOf(current.atDay(1).toString()) }
    var through by rememberSaveable { mutableStateOf(LocalDate.now(MonthlyReports.zone).toString()) }
    var showMonths by rememberSaveable { mutableStateOf(false) }
    val period = remember(selected, custom, from, through) {
        if (custom) MonthlyReports.Period(LocalDate.parse(from), LocalDate.parse(through))
        else MonthlyReports.month(YearMonth.parse(selected))
    }
    var totals by remember { mutableStateOf<MonthlyReports.Totals?>(null) }
    LaunchedEffect(entries, period) {
        totals = withContext(Dispatchers.Default) { MonthlyReports.totals(entries, period) }
    }
    fun pickDate(start: Boolean) {
        val date = LocalDate.parse(if (start) from else through)
        DatePickerDialog(context, { _, year, month, day ->
            val picked = LocalDate.of(year, month + 1, day)
            if (start) { from = picked.toString(); if (picked > LocalDate.parse(through)) through = from }
            else { through = picked.toString(); if (picked < LocalDate.parse(from)) from = through }
            custom = true
        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }
    if (showMonths) AlertDialog(onDismissRequest = { showMonths = false },
        title = { Text("اختيار الشهر") }, text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(MonthlyReports.availableMonths(current), key = { it.toString() }) { month ->
                    TextButton(onClick = { selected = month.toString(); custom = false; showMonths = false },
                        modifier = Modifier.fillMaxWidth()) {
                        Text(monthTitle(month, current))
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showMonths = false }) { Text("إغلاق") } })
    Scaffold(topBar = { ScreenTopBar("التقارير الشهرية") }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text("حركات الشهر المحدد فقط", style = MaterialTheme.typography.titleLarge)
                Text("تُحسب الديون والتحصيلات من تاريخ كل عملية. الرصيد الافتتاحي للحساب ليس حركة شهرية.",
                    style = MaterialTheme.typography.bodySmall)
            }
            item { Button(onClick = { showMonths = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (custom) "اختيار شهر بدل الفترة" else monthTitle(YearMonth.parse(selected), current))
            } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { selected = current.toString(); custom = false }, modifier = Modifier.weight(1f)) { Text("الشهر الحالي") }
                OutlinedButton(onClick = { selected = current.minusMonths(1).toString(); custom = false },
                    enabled = current.minusMonths(1) >= MonthlyReports.firstMonth, modifier = Modifier.weight(1f)) { Text("الشهر السابق") }
            } }
            item { OutlinedButton(onClick = { custom = !custom }, modifier = Modifier.fillMaxWidth()) { Text("تحديد فترة زمنية") } }
            if (custom) item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate(true) }, modifier = Modifier.fillMaxWidth()) { Text("من يوم: $from") }
                OutlinedButton(onClick = { pickDate(false) }, modifier = Modifier.fillMaxWidth()) { Text("إلى يوم: $through") }
            } }
            item { Text("من ${period.from} إلى ${period.through} • شامل اليومين • توقيت بغداد",
                style = MaterialTheme.typography.bodySmall) }
            item { val value = totals
                if (value == null) CircularProgressIndicator() else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MonthlyMetric("الديون المسجلة في الفترة", value.debts, value.debtCount, security.hideAmounts)
                    MonthlyMetric("التحصيلات المسجلة في الفترة", value.payments, value.paymentCount, security.hideAmounts)
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("صافي حركات الفترة (ديون − تحصيلات)")
                            Text(if (security.hideAmounts) "•••• د.ع" else formatMoney(value.debts - value.payments),
                                style = MaterialTheme.typography.titleLarge)
                            Text("هذا الفرق ليس الرصيد المتبقي لجميع حسابات الصيدلية.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun monthTitle(month: YearMonth, current: YearMonth): String {
    val names = listOf("كانون الثاني", "شباط", "آذار", "نيسان", "أيار", "حزيران", "تموز", "آب", "أيلول", "تشرين الأول", "تشرين الثاني", "كانون الأول")
    val prefix = when (month) { current -> "الشهر الحالي • "; current.minusMonths(1) -> "الشهر السابق • "; else -> "" }
    return prefix + names[month.monthValue - 1] + " ${month.year}"
}

@Composable
private fun MonthlyMetric(title: String, amount: Long, count: Int, hidden: Boolean) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(if (hidden) "•••• د.ع" else formatMoney(amount), style = MaterialTheme.typography.headlineSmall)
            Text("عدد العمليات: $count", style = MaterialTheme.typography.bodySmall)
        }
    }
}

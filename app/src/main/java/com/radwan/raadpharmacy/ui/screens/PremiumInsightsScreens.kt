package com.radwan.raadpharmacy.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.ui.components.MetricCard
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.components.SectionTitle
import com.radwan.raadpharmacy.ui.theme.DebtRed
import com.radwan.raadpharmacy.ui.theme.PaidGreen
import com.radwan.raadpharmacy.util.formatMoney
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private enum class ReportPeriodV3(val label: String, val days: Long?) {
    TODAY("اليوم", 1L),
    WEEK("7 أيام", 7L),
    MONTH("30 يومًا", 30L),
    QUARTER("90 يومًا", 90L)
}

private data class ReportSnapshotV3(
    val debts: Long,
    val collections: Long,
    val bottles: Int,
    val newCustomers: Int,
    val topCustomer: Customer?,
    val topArea: Pair<String, Long>?
)

@Composable
fun ReportsScreenV3(vm: PharmacyLedgerViewModel) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    var period by remember { mutableStateOf(ReportPeriodV3.MONTH) }

    val startMillis = remember(period) {
        if (period == ReportPeriodV3.TODAY) {
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } else {
            System.currentTimeMillis() - (period.days ?: 30L) * 24L * 60L * 60L * 1000L
        }
    }

    val snapshot = remember(customers, entries, startMillis) {
        val selectedEntries = entries.filter { it.createdAt >= startMillis }
        val debts = selectedEntries.asSequence()
            .filter { it.type == EntryType.DEBT }
            .sumOf { it.amount }
        val collections = selectedEntries.asSequence()
            .filter { it.type == EntryType.PAYMENT }
            .sumOf { it.amount }
        val bottles = selectedEntries.asSequence()
            .filter { it.type == EntryType.DEBT }
            .sumOf { it.bottles ?: 0 }
        val newCustomers = customers.count { it.createdAt >= startMillis }
        val topCustomer = vm.topDebtors().firstOrNull()
        val topArea = customers
            .groupBy { it.area.ifBlank { "غير محددة" } }
            .mapValues { (_, list) -> list.sumOf { vm.balance(it) } }
            .maxByOrNull { it.value }
            ?.let { it.key to it.value }

        ReportSnapshotV3(debts, collections, bottles, newCustomers, topCustomer, topArea)
    }

    val maxBar = maxOf(snapshot.debts, snapshot.collections, 1L).toFloat()
    val net = snapshot.debts - snapshot.collections

    Scaffold(topBar = { ScreenTopBar("التقارير") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReportPeriodV3.entries.forEach { option ->
                        FilterChip(
                            selected = period == option,
                            onClick = { period = option },
                            label = { Text(option.label) }
                        )
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "صافي حركة الفترة",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            formatMoney(net),
                            style = MaterialTheme.typography.headlineLarge,
                            color = if (net > 0) DebtRed else PaidGreen
                        )
                        Text(
                            if (net > 0) "الديون المسجلة أعلى من التحصيلات"
                            else "التحصيلات تساوي أو تتجاوز الديون المسجلة",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard(
                        "ديون الفترة",
                        formatMoney(snapshot.debts),
                        Icons.Rounded.ReceiptLong,
                        Modifier.weight(1f)
                    )
                    MetricCard(
                        "التحصيلات",
                        formatMoney(snapshot.collections),
                        Icons.Rounded.TrendingUp,
                        Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard(
                        "الدين الحالي",
                        formatMoney(vm.totalDebt()),
                        Icons.Rounded.AccountBalanceWallet,
                        Modifier.weight(1f)
                    )
                    MetricCard(
                        "زبائن جدد",
                        snapshot.newCustomers.toString(),
                        Icons.Rounded.People,
                        Modifier.weight(1f)
                    )
                }
            }

            item { SectionTitle("مقارنة الفترة") }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        V3ReportBar("الديون", snapshot.debts, snapshot.debts.toFloat() / maxBar, DebtRed)
                        V3ReportBar("التحصيلات", snapshot.collections, snapshot.collections.toFloat() / maxBar, PaidGreen)
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard(
                        "العناصر",
                        snapshot.bottles.toString(),
                        Icons.Rounded.BarChart,
                        Modifier.weight(1f)
                    )
                    MetricCard(
                        "الحسابات المفتوحة",
                        vm.indebtedCustomersCount().toString(),
                        Icons.Rounded.People,
                        Modifier.weight(1f)
                    )
                }
            }

            item { SectionTitle("أبرز الحسابات") }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        V3InsightLine(
                            "أعلى منطقة مديونية",
                            snapshot.topArea?.let { it.first + " • " + formatMoney(it.second) } ?: "لا توجد بيانات"
                        )
                        V3InsightLine(
                            "أعلى زبون مديونية",
                            snapshot.topCustomer?.let { it.name + " • " + formatMoney(vm.balance(it)) }
                                ?: "لا توجد حسابات مفتوحة"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun V3ReportBar(
    label: String,
    value: Long,
    fraction: Float,
    color: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(formatMoney(value), style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun V3InsightLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SettingsScreenV3(vm: PharmacyLedgerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showResetConfirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("إعادة البيانات التجريبية") },
            text = { Text("سيتم حذف البيانات الحالية وإعادة بيانات العرض الأولية.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        vm.resetDemoData()
                        showResetConfirm = false
                        message = "تمت إعادة البيانات التجريبية."
                    }
                }) { Text("إعادة") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("إلغاء") }
            }
        )
    }

    if (message != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("دفتر صيدلية رعد") },
            text = { Text(message.orEmpty()) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("حسنًا") } }
        )
    }

    fun exportBackup() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, vm.exportJson())
        }
        context.startActivity(Intent.createChooser(intent, "تصدير نسخة بيانات دفتر صيدلية رعد"))
    }

    Scaffold(topBar = { ScreenTopBar("المزيد") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.Info,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(25.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("دفتر صيدلية رعد", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "الإصدار 1.7.0 • كشف حساب احترافي",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            item { SectionTitle("البيانات") }
            item { V3SettingsRow(Icons.Rounded.Backup, "نسخة احتياطية", "مشاركة نسخة JSON كاملة", ::exportBackup) }
            item {
                V3SettingsRow(Icons.Rounded.Restore, "استعادة نسخة", "ستتوفر في مرحلة لاحقة") {
                    message = "الاستعادة من ملف لم تُفعّل بعد."
                }
            }
            item {
                V3SettingsRow(Icons.Rounded.Share, "كشف الحساب", "المشاركة من داخل حساب الزبون") {
                    message = "افتح حساب الزبون ثم كشف الحساب للمشاركة."
                }
            }

            item { SectionTitle("التطبيق") }
            item {
                V3SettingsRow(Icons.Rounded.DarkMode, "المظهر", "يتبع إعداد الهاتف تلقائيًا") {
                    message = "الوضع الفاتح والداكن يتبعان إعداد الهاتف."
                }
            }
            item {
                V3SettingsRow(Icons.Rounded.AccountBalanceWallet, "العملة", "الدينار العراقي • د.ع") { }
            }
            item {
                V3SettingsRow(Icons.Rounded.Lock, "قفل التطبيق", "غير مفعّل حاليًا") {
                    message = "PIN والبصمة غير مفعّلين في هذه النسخة."
                }
            }

            item {
                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text("إعادة البيانات التجريبية")
                }
            }
            item { Spacer(Modifier.height(6.dp)) }
        }
    }
}

@Composable
private fun V3SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronLeft, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

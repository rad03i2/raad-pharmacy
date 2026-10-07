package com.radwan.raadpharmacy.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.ui.components.CustomerCard
import com.radwan.raadpharmacy.ui.components.EmptyState
import com.radwan.raadpharmacy.ui.components.MetricCard
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.components.SectionTitle
import com.radwan.raadpharmacy.ui.theme.DebtRed
import com.radwan.raadpharmacy.ui.theme.PaidGreen
import com.radwan.raadpharmacy.util.daysSince
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatMoney
import com.radwan.raadpharmacy.util.normalizeIraqPhone
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private data class ReportSnapshot(
    val debts: Long,
    val collections: Long,
    val bottles: Int,
    val newCustomers: Int,
    val topCustomer: Customer?,
    val topArea: Pair<String, Long>?
)

@Composable
fun SmartSearchScreen(
    vm: PharmacyLedgerViewModel,
    onBack: () -> Unit,
    onCustomer: (String) -> Unit,
    onDebt: (String) -> Unit,
    onPayment: (String) -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val results = remember(customers, entries, query) {
        if (query.isBlank()) {
            customers.sortedByDescending { vm.lastEntryFor(it.id)?.createdAt ?: it.createdAt }.take(8)
        } else {
            val digits = query.filter(Char::isDigit)
            customers.asSequence()
                .filter {
                    it.name.contains(query, ignoreCase = true) ||
                        (digits.isNotBlank() && it.phone.orEmpty().contains(digits)) ||
                        it.area.contains(query, ignoreCase = true)
                }
                .sortedByDescending { vm.balance(it) }
                .toList()
        }
    }

    Scaffold(topBar = { ScreenTopBar("البحث السريع", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("الاسم، الهاتف، أو المنطقة") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    shape = MaterialTheme.shapes.medium
                )
            }

            item {
                Text(
                    if (query.isBlank()) "آخر الحسابات" else "النتائج • " + results.size.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (results.isEmpty()) {
                item { EmptyState("لا توجد نتيجة", "جرّب جزءًا من الاسم أو رقم الهاتف أو المنطقة.") }
            } else {
                items(results, key = { it.id }) { customer ->
                    SearchResultCard(
                        customer = customer,
                        balance = vm.balance(customer),
                        onOpen = { onCustomer(customer.id) },
                        onDebt = { onDebt(customer.id) },
                        onPayment = { onPayment(customer.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    customer: Customer,
    balance: Long,
    onOpen: () -> Unit,
    onDebt: () -> Unit,
    onPayment: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CustomerCard(customer, balance, onClick = onOpen)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(
                    onClick = onDebt,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium
                ) { Text("دين") }
                OutlinedButton(
                    onClick = onPayment,
                    enabled = balance > 0,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium
                ) { Text("تحصيل") }
                Button(
                    onClick = onOpen,
                    modifier = Modifier.weight(1.2f),
                    shape = MaterialTheme.shapes.medium
                ) { Text("فتح") }
            }
        }
    }
}

private enum class ReportPeriod(val label: String, val days: Long?) {
    TODAY("اليوم", 1L),
    WEEK("7 أيام", 7L),
    MONTH("30 يومًا", 30L),
    QUARTER("90 يومًا", 90L)
}

@Composable
fun ReportsScreen(vm: PharmacyLedgerViewModel) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val allEntries by vm.entries.collectAsStateWithLifecycle()
    var period by remember { mutableStateOf(ReportPeriod.MONTH) }

    val startMillis = remember(period) {
        if (period == ReportPeriod.TODAY) {
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } else {
            System.currentTimeMillis() - (period.days ?: 30L) * 24L * 60L * 60L * 1000L
        }
    }

    val snapshot = remember(customers, allEntries, startMillis) {
        val periodEntries = allEntries.filter { it.createdAt >= startMillis }
        val debts = periodEntries.asSequence()
            .filter { it.type == EntryType.DEBT }
            .sumOf { it.amount }
        val collections = periodEntries.asSequence()
            .filter { it.type == EntryType.PAYMENT }
            .sumOf { it.amount }
        val bottles = periodEntries.asSequence()
            .filter { it.type == EntryType.DEBT }
            .sumOf { it.bottles ?: 0 }
        val newCustomers = customers.count { it.createdAt >= startMillis }
        val topCustomer = vm.topDebtors().firstOrNull()
        val topArea = customers
            .groupBy { it.area.ifBlank { "غير محددة" } }
            .mapValues { (_, list) -> list.sumOf { vm.balance(it) } }
            .maxByOrNull { it.value }
            ?.let { it.key to it.value }

        ReportSnapshot(debts, collections, bottles, newCustomers, topCustomer, topArea)
    }
    val maxBar = maxOf(snapshot.debts, snapshot.collections, 1L).toFloat()

    Scaffold(topBar = { ScreenTopBar("التقارير") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    ReportPeriod.entries.forEach { option ->
                        FilterChip(
                            selected = period == option,
                            onClick = { period = option },
                            label = { Text(option.label) }
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    MetricCard("ديون الفترة", formatMoney(snapshot.debts), Icons.Rounded.ReceiptLong, Modifier.weight(1f))
                    MetricCard("التحصيلات", formatMoney(snapshot.collections), Icons.Rounded.TrendingUp, Modifier.weight(1f))
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    MetricCard("الدين الحالي", formatMoney(vm.totalDebt()), Icons.Rounded.AccountBalanceWallet, Modifier.weight(1f))
                    MetricCard("زبائن جدد", snapshot.newCustomers.toString(), Icons.Rounded.People, Modifier.weight(1f))
                }
            }

            item {
                MetricCard(
                    "العناصر المسجلة",
                    snapshot.bottles.toString(),
                    Icons.Rounded.BarChart,
                    Modifier.fillMaxWidth()
                )
            }

            item { SectionTitle("حركة الفترة") }
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
                        ReportBar("الديون", snapshot.debts, snapshot.debts.toFloat() / maxBar, DebtRed)
                        ReportBar("التحصيلات", snapshot.collections, snapshot.collections.toFloat() / maxBar, PaidGreen)
                    }
                }
            }

            item { SectionTitle("الأبرز حاليًا") }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        InsightLine(
                            "أعلى منطقة مديونية",
                            snapshot.topArea?.let { it.first + " • " + formatMoney(it.second) } ?: "لا توجد بيانات"
                        )
                        InsightLine(
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
private fun ReportBar(
    label: String,
    value: Long,
    fraction: Float,
    color: androidx.compose.ui.graphics.Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(formatMoney(value), style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(7.dp),
            color = color
        )
    }
}

@Composable
private fun InsightLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun FollowUpScreen(
    vm: PharmacyLedgerViewModel,
    onBack: () -> Unit,
    onCustomer: (String) -> Unit
) {
    val context = LocalContext.current
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("لم يسدد منذ 30 يومًا") }

    fun daysSincePayment(customer: Customer): Long {
        val lastPayment = vm.lastPaymentFor(customer.id)
        return if (lastPayment != null) daysSince(lastPayment.createdAt) else daysSince(customer.createdAt)
    }

    val candidates = remember(customers, entries, filter) {
        val indebted = customers.filter { vm.balance(it) > 0 }
        when (filter) {
            "ديون قديمة" -> indebted.filter {
                val last = vm.lastEntryFor(it.id)
                last != null && daysSince(last.createdAt) >= 30
            }.sortedByDescending { vm.balance(it) }
            "أعلى الحسابات" -> indebted.sortedByDescending { vm.balance(it) }.take(10)
            "اليوم" -> indebted
                .sortedWith(compareByDescending<Customer> { daysSincePayment(it) }.thenByDescending { vm.balance(it) })
                .take(10)
            else -> indebted
                .filter { daysSincePayment(it) >= 30 }
                .sortedByDescending { daysSincePayment(it) }
        }
    }

    Scaffold(topBar = { ScreenTopBar("المتابعة", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            item {
                Text(
                    "الحسابات التي قد تحتاج اتصالًا أو تذكيرًا.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    listOf("لم يسدد منذ 30 يومًا", "ديون قديمة", "أعلى الحسابات", "اليوم").forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option) }
                        )
                    }
                }
            }

            if (candidates.isEmpty()) {
                item { EmptyState("لا توجد حسابات تحتاج متابعة", "لا توجد نتائج مطابقة لهذا التصنيف.") }
            } else {
                items(candidates, key = { it.id }) { customer ->
                    val lastPayment = vm.lastPaymentFor(customer.id)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(customer.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        customer.area.ifBlank { "بدون منطقة" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(formatMoney(vm.balance(customer)), color = DebtRed, style = MaterialTheme.typography.titleMedium)
                            }

                            Text(
                                "آخر دفعة: " +
                                    (lastPayment?.let { formatDate(it.createdAt) + " • " + formatMoney(it.amount) } ?: "لا توجد"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "منذ آخر تحصيل: " + daysSincePayment(customer).toString() + " يوم",
                                style = MaterialTheme.typography.bodySmall
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                IconButton(
                                    onClick = {
                                        customer.phone?.let {
                                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + it)))
                                        }
                                    },
                                    enabled = !customer.phone.isNullOrBlank()
                                ) {
                                    Icon(Icons.Rounded.Call, "اتصال")
                                }
                                IconButton(
                                    onClick = {
                                        customer.phone?.let {
                                            val phone = normalizeIraqPhone(it).removePrefix("+")
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + phone)))
                                            }
                                        }
                                    },
                                    enabled = !customer.phone.isNullOrBlank()
                                ) {
                                    Icon(Icons.Rounded.Chat, "WhatsApp")
                                }
                                Button(
                                    onClick = { onCustomer(customer.id) },
                                    modifier = Modifier.weight(1f),
                                    shape = MaterialTheme.shapes.medium
                                ) {
                                    Text("فتح الحساب")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: PharmacyLedgerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showResetConfirm by remember { mutableStateOf(false) }
    var infoMessage by remember { mutableStateOf<String?>(null) }

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
                        infoMessage = "تمت إعادة البيانات التجريبية."
                    }
                }) { Text("إعادة") }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("إلغاء") } }
        )
    }

    if (infoMessage != null) {
        AlertDialog(
            onDismissRequest = { infoMessage = null },
            title = { Text("دفتر صيدلية رعد") },
            text = { Text(infoMessage.orEmpty()) },
            confirmButton = { TextButton(onClick = { infoMessage = null }) { Text("حسنًا") } }
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { SettingsSectionTitle("البيانات") }
            item { SettingsRow(Icons.Rounded.Backup, "نسخة احتياطية", "مشاركة نسخة JSON من البيانات", ::exportBackup) }
            item {
                SettingsRow(Icons.Rounded.Restore, "استعادة نسخة", "سيتم تفعيل اختيار ملف النسخة في مرحلة لاحقة") {
                    infoMessage = "الاستعادة من ملف لم تُفعّل بعد، حتى لا نظهر ميزة غير مكتملة على أنها جاهزة."
                }
            }
            item {
                SettingsRow(Icons.Rounded.Share, "كشف الحساب", "المشاركة متاحة من داخل حساب كل زبون") {
                    infoMessage = "افتح الزبون ثم كشف الحساب للمشاركة عبر WhatsApp."
                }
            }

            item { SettingsSectionTitle("التطبيق") }
            item {
                SettingsRow(Icons.Rounded.DarkMode, "المظهر", "يتبع وضع الهاتف تلقائيًا") {
                    infoMessage = "التطبيق يتبع الوضع الفاتح أو الداكن المحدد في الهاتف."
                }
            }
            item {
                SettingsRow(Icons.Rounded.AccountBalanceWallet, "العملة", "الدينار العراقي • د.ع") { }
            }
            item {
                SettingsRow(Icons.Rounded.ReceiptLong, "الأرقام", "أرقام إنجليزية 0–9") { }
            }
            item {
                SettingsRow(Icons.Rounded.Lock, "قفل التطبيق", "غير مفعّل في هذه النسخة") {
                    infoMessage = "قفل PIN والبصمة لم يُفعّل بعد."
                }
            }

            item { SettingsSectionTitle("حول") }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text("دفتر صيدلية رعد", style = MaterialTheme.typography.titleMedium)
                                Text("الإصدار 1.2.0 • المرحلة 2", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(
                            "إدارة حسابات وديون زبائن الصيدلية.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("إعادة البيانات التجريبية")
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronLeft, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


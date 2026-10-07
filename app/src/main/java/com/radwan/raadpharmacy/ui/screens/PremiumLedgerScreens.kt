package com.radwan.raadpharmacy.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.ui.components.CustomerAvatar
import com.radwan.raadpharmacy.ui.components.EmptyState
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.components.SoftDivider
import com.radwan.raadpharmacy.ui.components.TransactionRow
import com.radwan.raadpharmacy.ui.theme.DebtRed
import com.radwan.raadpharmacy.ui.theme.PaidGreen
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatMoney

private enum class MovementFilterV4(val label: String) {
    ALL("الكل"),
    DEBTS("الديون"),
    PAYMENTS("التحصيلات")
}

@Composable
fun CustomerTransactionsScreenV4(
    vm: PharmacyLedgerViewModel,
    customerId: String,
    onBack: () -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val allEntries by vm.entries.collectAsStateWithLifecycle()
    val customer = customers.firstOrNull { it.id == customerId }

    if (customer == null) {
        V4MissingCustomer(onBack)
        return
    }

    var filter by remember { mutableStateOf(MovementFilterV4.ALL) }
    var recentOnly by remember { mutableStateOf(false) }
    val thirtyDaysAgo = remember {
        System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
    }

    val customerEntries = remember(allEntries, customerId) {
        allEntries.asSequence()
            .filter { it.customerId == customerId }
            .sortedBy { it.createdAt }
            .toList()
    }

    val balanceAfterByEntry = remember(customerEntries, customer.openingDebt) {
        var running = customer.openingDebt
        buildMap<String, Long> {
            customerEntries.forEach { entry ->
                running += if (entry.type == EntryType.DEBT) entry.amount else -entry.amount
                put(entry.id, running.coerceAtLeast(0L))
            }
        }
    }

    val visibleEntries = remember(customerEntries, filter, recentOnly, thirtyDaysAgo) {
        customerEntries.asSequence()
            .filter {
                when (filter) {
                    MovementFilterV4.ALL -> true
                    MovementFilterV4.DEBTS -> it.type == EntryType.DEBT
                    MovementFilterV4.PAYMENTS -> it.type == EntryType.PAYMENT
                }
            }
            .filter { !recentOnly || it.createdAt >= thirtyDaysAgo }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    val currentBalance = vm.balance(customer)
    val totalDebts = remember(customerEntries, customer.openingDebt) {
        customer.openingDebt + customerEntries.asSequence()
            .filter { it.type == EntryType.DEBT }
            .sumOf { it.amount }
    }
    val totalPaid = remember(customerEntries) {
        customerEntries.asSequence()
            .filter { it.type == EntryType.PAYMENT }
            .sumOf { it.amount }
    }

    Scaffold(
        topBar = { ScreenTopBar("سجل الحركات", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CustomerAvatar(
                                customerId = customer.id,
                                size = 44.dp
                            )
                            Column(
                                modifier = Modifier.weight(1f).padding(horizontal = 11.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(customer.name, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    customer.area.ifBlank { "بدون منطقة" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                formatMoney(currentBalance),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (currentBalance > 0) DebtRed else PaidGreen
                            )
                        }

                        SoftDivider()

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            V4LedgerStat("إجمالي الدين", totalDebts, false, Modifier.weight(1f))
                            V4LedgerStat("إجمالي المدفوع", totalPaid, true, Modifier.weight(1f))
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MovementFilterV4.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option.label) }
                        )
                    }
                    FilterChip(
                        selected = recentOnly,
                        onClick = { recentOnly = !recentOnly },
                        label = { Text("آخر 30 يومًا") }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "الحركات",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        visibleEntries.size.toString() + " حركة",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (visibleEntries.isEmpty()) {
                item {
                    EmptyState(
                        "لا توجد حركات",
                        "لا توجد عمليات مطابقة للفلاتر الحالية."
                    )
                }
            } else {
                items(visibleEntries, key = { it.id }) { entry ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        TransactionRow(
                            entry = entry,
                            showBalance = balanceAfterByEntry[entry.id],
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun V4LedgerStat(
    label: String,
    amount: Long,
    positive: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (positive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.errorContainer
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                if (positive) Icons.Rounded.Payments else Icons.Rounded.AccountBalanceWallet,
                contentDescription = null,
                tint = if (positive) PaidGreen else DebtRed,
                modifier = Modifier.size(18.dp)
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                formatMoney(amount),
                style = MaterialTheme.typography.titleSmall,
                color = if (positive) PaidGreen else DebtRed
            )
        }
    }
}

@Composable
fun StatementScreenV4(
    vm: PharmacyLedgerViewModel,
    customerId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val customers by vm.customers.collectAsStateWithLifecycle()
    val allEntries by vm.entries.collectAsStateWithLifecycle()
    val customer = customers.firstOrNull { it.id == customerId }

    if (customer == null) {
        V4MissingCustomer(onBack)
        return
    }

    val entries = remember(allEntries, customerId) {
        allEntries.asSequence()
            .filter { it.customerId == customerId }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    val totalDebts = remember(entries, customer.openingDebt) {
        customer.openingDebt + entries.asSequence()
            .filter { it.type == EntryType.DEBT }
            .sumOf { it.amount }
    }
    val totalPaid = remember(entries) {
        entries.asSequence()
            .filter { it.type == EntryType.PAYMENT }
            .sumOf { it.amount }
    }
    val balance = vm.balance(customer)

    fun shareStatement() {
        val message = buildString {
            appendLine("السلام عليكم")
            appendLine("كشف حساب الدين")
            appendLine("الزبون: " + customer.name)
            if (customer.area.isNotBlank()) appendLine("المنطقة: " + customer.area)
            appendLine("الدين الحالي: " + formatMoney(balance))
            appendLine("إجمالي الديون: " + formatMoney(totalDebts))
            appendLine("إجمالي المدفوع: " + formatMoney(totalPaid))
            appendLine("شكرًا لحسن تعاملكم 🌹")
        }
        val whatsapp = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            setPackage("com.whatsapp")
        }
        val fallback = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
            },
            "مشاركة كشف الحساب"
        )
        runCatching { context.startActivity(whatsapp) }
            .onFailure { context.startActivity(fallback) }
    }

    Scaffold(
        topBar = { ScreenTopBar("كشف الحساب", onBack) },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp
            ) {
                Button(
                    onClick = ::shareStatement,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("مشاركة عبر WhatsApp")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 1.dp
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("دفتر صيدلية رعد", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "كشف حساب • " + formatDate(System.currentTimeMillis()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = if (balance > 0) MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        if (balance > 0) Icons.Rounded.AccountBalanceWallet else Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = if (balance > 0) DebtRed else PaidGreen,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }
                            }
                        }

                        SoftDivider()

                        V4StatementLine("الزبون", customer.name)
                        if (!customer.phone.isNullOrBlank()) {
                            V4StatementLine("رقم الهاتف", customer.phone)
                        }
                        if (customer.area.isNotBlank()) {
                            V4StatementLine("المنطقة", customer.area)
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = if (balance > 0) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "الدين الحالي",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    formatMoney(balance),
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (balance > 0) DebtRed else PaidGreen
                                )
                                Text(
                                    if (balance > 0) "حساب مفتوح" else "الحساب مسدد بالكامل",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (balance > 0) DebtRed else PaidGreen
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            V4StatementMiniStat(
                                "إجمالي الديون",
                                totalDebts,
                                false,
                                Modifier.weight(1f)
                            )
                            V4StatementMiniStat(
                                "إجمالي المدفوع",
                                totalPaid,
                                true,
                                Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("آخر العمليات", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "آخر " + minOf(entries.size, 6).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (entries.isEmpty()) {
                item {
                    EmptyState(
                        "لا توجد عمليات",
                        "سيظهر سجل الحساب هنا بعد أول حركة."
                    )
                }
            } else {
                items(entries.take(6), key = { it.id }) { entry ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        TransactionRow(
                            entry,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun V4StatementMiniStat(
    label: String,
    amount: Long,
    positive: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                formatMoney(amount),
                style = MaterialTheme.typography.titleSmall,
                color = if (positive) PaidGreen else DebtRed
            )
        }
    }
}

@Composable
private fun V4StatementLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun V4MissingCustomer(onBack: () -> Unit) {
    Scaffold(topBar = { ScreenTopBar("الزبون", onBack) }) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text("تعذر العثور على الزبون.")
        }
    }
}

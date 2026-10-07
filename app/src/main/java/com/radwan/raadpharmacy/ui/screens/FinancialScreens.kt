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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.ui.components.EmptyState
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.components.SectionTitle
import com.radwan.raadpharmacy.ui.components.SoftDivider
import com.radwan.raadpharmacy.ui.components.TransactionRow
import com.radwan.raadpharmacy.ui.theme.DebtRed
import com.radwan.raadpharmacy.ui.theme.PaidGreen
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatMoney
import kotlinx.coroutines.launch

private enum class DebtEntryMode(val label: String) {
    AMOUNT("مبلغ مباشر"),
    BOTTLES("عناصر")
}

@Composable
fun AddDebtScreen(
    vm: PharmacyLedgerViewModel,
    customerId: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val customer = customers.firstOrNull { it.id == customerId }
    if (customer == null) {
        MissingCustomerScreen(onBack)
        return
    }

    val previousBalance = vm.balance(customer)
    var mode by remember { mutableStateOf(DebtEntryMode.AMOUNT) }
    var bottles by remember { mutableStateOf("") }
    var bottlePrice by remember { mutableStateOf("25000") }
    var amountText by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var savedAmount by remember { mutableStateOf<Long?>(null) }

    val bottleCount = bottles.toIntOrNull() ?: 0
    val onePrice = bottlePrice.toLongOrNull() ?: 0L
    val bottleTotal = if (bottleCount > 0 && onePrice > 0) bottleCount * onePrice else 0L
    val debtAmount = if (mode == DebtEntryMode.AMOUNT) amountText.toLongOrNull() ?: 0L else bottleTotal

    savedAmount?.let { saved ->
        AlertDialog(
            onDismissRequest = {},
            icon = {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = PaidGreen,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            },
            title = { Text("تم تسجيل الدين") },
            text = { Text("أضيف " + formatMoney(saved) + " إلى حساب " + customer.name + ".") },
            confirmButton = { TextButton(onClick = onBack) { Text("العودة للحساب") } }
        )
    }

    Scaffold(topBar = { ScreenTopBar("إضافة دين", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                FinanceHeader(
                    name = customer.name,
                    label = "الدين الحالي",
                    value = previousBalance,
                    debt = true
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DebtEntryMode.entries.forEach { option ->
                        FilterChip(
                            selected = mode == option,
                            onClick = {
                                mode = option
                                showError = false
                            },
                            label = { Text(option.label) },
                            leadingIcon = {
                                Icon(
                                    if (option == DebtEntryMode.AMOUNT) Icons.Rounded.Payments else Icons.Rounded.LocalShipping,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }
            }

            if (mode == DebtEntryMode.AMOUNT) {
                item {
                    AmountField(
                        value = amountText,
                        onValueChange = {
                            amountText = it.filter(Char::isDigit).take(12)
                            showError = false
                        },
                        label = "مبلغ الدين",
                        isError = showError,
                        errorText = if (showError) "أدخل مبلغًا أكبر من صفر" else null
                    )
                }
                item {
                    QuickAmounts(
                        values = listOf(5_000L, 10_000L, 15_000L, 20_000L, 25_000L, 50_000L),
                        selected = amountText.toLongOrNull(),
                        onSelect = {
                            amountText = it.toString()
                            showError = false
                        }
                    )
                }
            } else {
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(15.dp),
                            verticalArrangement = Arrangement.spacedBy(11.dp)
                        ) {
                            Text("تفاصيل المشتريات", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                OutlinedTextField(
                                    value = bottles,
                                    onValueChange = {
                                        bottles = it.filter(Char::isDigit).take(3)
                                        showError = false
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("العدد") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = MaterialTheme.shapes.medium
                                )
                                OutlinedTextField(
                                    value = bottlePrice,
                                    onValueChange = {
                                        bottlePrice = it.filter(Char::isDigit).take(9)
                                        showError = false
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("السعر") },
                                    suffix = { Text("د.ع") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = MaterialTheme.shapes.medium
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("الإجمالي", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatMoney(bottleTotal), style = MaterialTheme.typography.titleLarge)
                            }
                            if (showError) {
                                Text("أدخل عدد العناصر وسعرها.", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            item {
                BalanceEquation(
                    firstLabel = "الدين السابق",
                    first = previousBalance,
                    operator = "+",
                    secondLabel = "الدين الجديد",
                    second = debtAmount,
                    resultLabel = "الرصيد الجديد",
                    result = previousBalance + debtAmount,
                    resultColorPositive = false
                )
            }

            item {
                Button(
                    onClick = {
                        if (debtAmount <= 0L) {
                            showError = true
                        } else {
                            scope.launch {
                                vm.addDebt(
                                    customerId = customerId,
                                    amount = debtAmount,
                                    bottles = bottleCount.takeIf { mode == DebtEntryMode.BOTTLES && it > 0 },
                                    bottlePrice = onePrice.takeIf { mode == DebtEntryMode.BOTTLES && it > 0 }
                                )
                                savedAmount = debtAmount
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("تسجيل الدين", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun AddPaymentScreen(
    vm: PharmacyLedgerViewModel,
    customerId: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val customer = customers.firstOrNull { it.id == customerId }
    if (customer == null) {
        MissingCustomerScreen(onBack)
        return
    }

    val currentBalance = vm.balance(customer)
    var amountText by remember { mutableStateOf("") }
    var savedAmount by remember { mutableStateOf<Long?>(null) }

    val amount = amountText.toLongOrNull() ?: 0L
    val tooHigh = amount > currentBalance && amount > 0
    val remaining = (currentBalance - amount).coerceAtLeast(0L)

    savedAmount?.let { saved ->
        AlertDialog(
            onDismissRequest = {},
            icon = {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = PaidGreen,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            },
            title = { Text(if (saved == currentBalance) "تم تسديد الحساب" else "تم تسجيل التحصيل") },
            text = {
                Text(
                    if (saved == currentBalance) "أصبح رصيد " + customer.name + " صفرًا."
                    else "تم تحصيل " + formatMoney(saved) + " من " + customer.name + "."
                )
            },
            confirmButton = { TextButton(onClick = onBack) { Text("العودة للحساب") } }
        )
    }

    Scaffold(topBar = { ScreenTopBar("تسجيل تحصيل", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                FinanceHeader(
                    name = customer.name,
                    label = "المبلغ المطلوب",
                    value = currentBalance,
                    debt = currentBalance > 0
                )
            }

            if (currentBalance == 0L) {
                item { EmptyState("الحساب مسدد", "لا يوجد مبلغ متبقٍ على هذا الزبون.") }
            } else {
                item {
                    AmountField(
                        value = amountText,
                        onValueChange = { amountText = it.filter(Char::isDigit).take(12) },
                        label = "المبلغ المستلم",
                        isError = tooHigh,
                        errorText = if (tooHigh) "المبلغ أكبر من الدين الحالي" else "الحد الأعلى " + formatMoney(currentBalance)
                    )
                }

                item {
                    QuickAmounts(
                        values = listOf(5_000L, 10_000L, 25_000L, currentBalance).distinct(),
                        selected = amountText.toLongOrNull(),
                        onSelect = { amountText = it.coerceAtMost(currentBalance).toString() },
                        fullAmount = currentBalance
                    )
                }

                item {
                    BalanceEquation(
                        firstLabel = "الدين الحالي",
                        first = currentBalance,
                        operator = "-",
                        secondLabel = "التحصيل",
                        second = amount,
                        resultLabel = "المتبقي",
                        result = remaining,
                        resultColorPositive = remaining == 0L && amount > 0
                    )
                }

                if (remaining == 0L && amount > 0 && !tooHigh) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Rounded.Check, null, tint = PaidGreen)
                                Text("هذه الدفعة ستغلق الحساب بالكامل.", color = PaidGreen)
                            }
                        }
                    }
                }

                item {
                    Button(
                        onClick = {
                            scope.launch {
                                if (vm.addPayment(customerId, amount)) savedAmount = amount
                            }
                        },
                        enabled = amount > 0 && amount <= currentBalance,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("تأكيد التحصيل", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private enum class MovementFilter(val label: String) {
    ALL("الكل"),
    DEBTS("ديون"),
    PAYMENTS("تحصيلات")
}

@Composable
fun CustomerTransactionsScreen(
    vm: PharmacyLedgerViewModel,
    customerId: String,
    onBack: () -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val allEntries by vm.entries.collectAsStateWithLifecycle()
    val customer = customers.firstOrNull { it.id == customerId }
    if (customer == null) {
        MissingCustomerScreen(onBack)
        return
    }

    var filter by remember { mutableStateOf(MovementFilter.ALL) }
    var recentOnly by remember { mutableStateOf(false) }
    val thirtyDaysAgo = remember { System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L }

    val customerEntries = remember(allEntries, customerId) {
        allEntries.filter { it.customerId == customerId }.sortedBy { it.createdAt }
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
                    MovementFilter.ALL -> true
                    MovementFilter.DEBTS -> it.type == EntryType.DEBT
                    MovementFilter.PAYMENTS -> it.type == EntryType.PAYMENT
                }
            }
            .filter { !recentOnly || it.createdAt >= thirtyDaysAgo }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    Scaffold(topBar = { ScreenTopBar("حركات " + customer.name, onBack) }) { padding ->
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
                    MovementFilter.entries.forEach { option ->
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
                Text(
                    visibleEntries.size.toString() + " حركة",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (visibleEntries.isEmpty()) {
                item { EmptyState("لا توجد حركات", "غيّر الفلتر أو أضف حركة جديدة.") }
            } else {
                items(visibleEntries, key = { it.id }) { entry ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
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
fun StatementScreen(
    vm: PharmacyLedgerViewModel,
    customerId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val customers by vm.customers.collectAsStateWithLifecycle()
    val allEntries by vm.entries.collectAsStateWithLifecycle()
    val customer = customers.firstOrNull { it.id == customerId }
    if (customer == null) {
        MissingCustomerScreen(onBack)
        return
    }

    val entries = remember(allEntries, customerId) {
        allEntries.filter { it.customerId == customerId }.sortedByDescending { it.createdAt }
    }
    val totals = remember(entries, customer.openingDebt) {
        val debts = customer.openingDebt + entries.asSequence()
            .filter { it.type == EntryType.DEBT }
            .sumOf { it.amount }
        val paid = entries.asSequence()
            .filter { it.type == EntryType.PAYMENT }
            .sumOf { it.amount }
        debts to paid
    }
    val totalDebts = totals.first
    val totalPaid = totals.second
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

    Scaffold(topBar = { ScreenTopBar("كشف الحساب", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
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
                        verticalArrangement = Arrangement.spacedBy(11.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("دفتر صيدلية رعد", style = MaterialTheme.typography.titleLarge)
                                Text("كشف حساب", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatDate(System.currentTimeMillis()), style = MaterialTheme.typography.bodySmall)
                        }
                        SoftDivider()
                        StatementLine("الزبون", customer.name)
                        if (!customer.phone.isNullOrBlank()) StatementLine("الهاتف", customer.phone)
                        if (customer.area.isNotBlank()) StatementLine("المنطقة", customer.area)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = if (balance > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(17.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("الدين الحالي", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    formatMoney(balance),
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = if (balance > 0) DebtRed else PaidGreen
                                )
                            }
                        }
                        StatementLine("إجمالي الديون", formatMoney(totalDebts))
                        StatementLine("إجمالي المدفوع", formatMoney(totalPaid))
                    }
                }
            }

            item { SectionTitle("آخر العمليات") }
            if (entries.isEmpty()) {
                item { Text("لا توجد عمليات مسجلة.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(entries.take(6), key = { it.id }) { entry ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        TransactionRow(entry, modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
            }

            item {
                Button(
                    onClick = ::shareStatement,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Rounded.Share, null)
                    Spacer(Modifier.size(7.dp))
                    Text("مشاركة عبر WhatsApp")
                }
            }
        }
    }
}

@Composable
private fun FinanceHeader(
    name: String,
    label: String,
    value: Long,
    debt: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                formatMoney(value),
                style = MaterialTheme.typography.titleLarge,
                color = if (debt) DebtRed else PaidGreen
            )
        }
    }
}

@Composable
private fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    errorText: String?
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        suffix = { Text("د.ع") },
        singleLine = true,
        isError = isError,
        supportingText = errorText?.let { message ->
            {
                Text(
                    message,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Start),
        shape = MaterialTheme.shapes.medium
    )
}

@Composable
private fun QuickAmounts(
    values: List<Long>,
    selected: Long?,
    onSelect: (Long) -> Unit,
    fullAmount: Long? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("اختيار سريع", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            values.forEach { amount ->
                FilterChip(
                    selected = selected == amount,
                    onClick = { onSelect(amount) },
                    label = {
                        Text(
                            if (fullAmount != null && amount == fullAmount) "الكل • " + formatMoney(amount)
                            else formatMoney(amount)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun BalanceEquation(
    firstLabel: String,
    first: Long,
    operator: String,
    secondLabel: String,
    second: Long,
    resultLabel: String,
    result: Long,
    resultColorPositive: Boolean
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            StatementLine(firstLabel, formatMoney(first))
            StatementLine(operator + " " + secondLabel, formatMoney(second))
            SoftDivider()
            StatementLine(
                resultLabel,
                formatMoney(result),
                bold = true,
                valueColor = if (resultColorPositive) PaidGreen else DebtRed
            )
        }
    }
}

@Composable
private fun StatementLine(
    label: String,
    value: String,
    bold: Boolean = false,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            color = valueColor,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun MissingCustomerScreen(onBack: () -> Unit) {
    Scaffold(topBar = { ScreenTopBar("الزبون", onBack) }) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text("تعذر العثور على الزبون.")
        }
    }
}

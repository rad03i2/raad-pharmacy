package com.radwan.raadpharmacy.ui.screens

import com.radwan.raadpharmacy.ui.components.rememberLedgerFlingBehavior

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.data.LedgerEntry
import com.radwan.raadpharmacy.ui.components.EmptyState
import com.radwan.raadpharmacy.ui.components.QuickActionCard
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.components.SectionTitle
import com.radwan.raadpharmacy.ui.components.StatusChip
import com.radwan.raadpharmacy.ui.components.SoftDivider
import com.radwan.raadpharmacy.ui.components.TransactionRow
import com.radwan.raadpharmacy.ui.theme.DebtRed
import com.radwan.raadpharmacy.ui.theme.PaidGreen
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatMoney
import com.radwan.raadpharmacy.util.normalizeIraqPhone
import kotlinx.coroutines.launch

@Composable
fun CustomerProfileScreenV6(
    vm: PharmacyLedgerViewModel,
    customerId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onAddDebt: () -> Unit,
    onPayment: () -> Unit,
    onTransactions: () -> Unit,
    onStatement: () -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val security by vm.securityState.collectAsStateWithLifecycle()
    val customer = customers.firstOrNull { it.id == customerId }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showEdit by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    if (customer == null) {
        V6MissingCustomer(onBack)
        return
    }

    if (showEdit) {
        EditCustomerDialog(
            customer = customer,
            hasMovements = entries.any { it.customerId == customer.id },
            onDismiss = { showEdit = false },
            onSave = { name, phone, area, address, openingDebt, notes ->
                scope.launch {
                    val result = vm.updateCustomer(
                        customer.id, name, phone, area, address, openingDebt, notes
                    )
                    message = result.message
                    if (result.success) showEdit = false
                }
            }
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("حذف الزبون؟") },
            text = {
                Text(
                    "يُسمح بالحذف فقط عندما لا توجد أي حركات مالية ولا دين سابق. " +
                        "هذا الشرط يمنع فقدان السجل المالي بالخطأ."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val result = vm.deleteCustomer(customer.id)
                        showDelete = false
                        if (result.success) onDeleted() else message = result.message
                    }
                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("إلغاء") }
            }
        )
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("دفتر صيدلية رعد") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text("حسنًا") }
            }
        )
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
                    IconButton(onClick = { showEdit = true }) {
                        Icon(Icons.Rounded.Edit, "تعديل الزبون")
                    }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            "حذف الزبون",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    if (!customer.phone.isNullOrBlank()) {
                        IconButton(onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + customer.phone))
                            )
                        }) { Icon(Icons.Rounded.Call, "اتصال") }
                        IconButton(onClick = {
                            val phone = normalizeIraqPhone(customer.phone).removePrefix("+")
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + phone))
                                )
                            }
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { V6AccountHero(customer, balance, security.hideAmounts) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    V6AccountAction(
                        "إضافة دين", Icons.Rounded.Add, onAddDebt,
                        Modifier.weight(1f), false
                    )
                    V6AccountAction(
                        "تحصيل", Icons.Rounded.Wallet, onPayment,
                        Modifier.weight(1f), true, balance > 0
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickActionCard(
                        "كشف الحساب", Icons.Rounded.ReceiptLong,
                        onStatement, Modifier.weight(1f)
                    )
                    QuickActionCard(
                        "إدارة الحركات", Icons.Rounded.Assessment,
                        onTransactions, Modifier.weight(1f)
                    )
                }
            }

            item { SectionTitle("معلومات الزبون", "تعديل") { showEdit = true } }

            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(11.dp)
                    ) {
                        V6ProfileInfo("المنطقة", customer.area.ifBlank { "غير محددة" })
                        V6ProfileInfo("الهاتف", customer.phone ?: "غير مضاف")
                        V6ProfileInfo(
                            "الدين السابق",
                            if (security.hideAmounts) "•••• د.ع" else formatMoney(customer.openingDebt)
                        )
                        if (customer.address.isNotBlank()) V6ProfileInfo("المكان", customer.address)
                        if (customer.notes.isNotBlank()) V6ProfileInfo("ملاحظات", customer.notes)
                    }
                }
            }

            item { SectionTitle("آخر الحركات", "إدارة الكل", onTransactions) }

            if (customerEntries.isEmpty()) {
                item { EmptyState("لا توجد حركات", "أضف دينًا أو تحصيلًا ليظهر هنا.") }
            } else {
                items(customerEntries.take(5), key = { it.id }) { entry ->
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        TransactionRow(
                            entry,
                            modifier = Modifier.padding(horizontal = 14.dp),
                            hideAmounts = security.hideAmounts
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditCustomerDialog(
    customer: Customer,
    hasMovements: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String?, String, String, Long, String) -> Unit
) {
    var name by remember(customer.id, customer.name) { mutableStateOf(customer.name) }
    var phone by remember(customer.id, customer.phone) { mutableStateOf(customer.phone.orEmpty()) }
    var area by remember(customer.id, customer.area) { mutableStateOf(customer.area) }
    var address by remember(customer.id, customer.address) { mutableStateOf(customer.address) }
    var openingDebt by remember(customer.id, customer.openingDebt) { mutableStateOf(customer.openingDebt.toString()) }
    var notes by remember(customer.id, customer.notes) { mutableStateOf(customer.notes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل بيانات الزبون") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("الاسم *") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("رقم الهاتف") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                OutlinedTextField(
                    value = area,
                    onValueChange = { area = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("المنطقة") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("العنوان") },
                    maxLines = 2
                )
                OutlinedTextField(
                    value = openingDebt,
                    onValueChange = { openingDebt = it.filter(Char::isDigit).take(12) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("الدين السابق") },
                    suffix = { Text("د.ع") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        if (hasMovements) {
                            Text("سيتم فحص جميع الحركات قبل قبول تغيير الدين السابق.")
                        }
                    }
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("ملاحظات") },
                    maxLines = 2
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        name,
                        phone.takeIf { it.isNotBlank() },
                        area,
                        address,
                        openingDebt.toLongOrNull() ?: 0L,
                        notes
                    )
                },
                enabled = name.isNotBlank()
            ) { Text("حفظ التعديلات") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

private enum class MovementFilterV6(val label: String) {
    ALL("الكل"),
    DEBTS("الديون"),
    PAYMENTS("التحصيلات")
}

@Composable
fun CustomerTransactionsScreenV6(
    vm: PharmacyLedgerViewModel,
    customerId: String,
    onBack: () -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val allEntries by vm.entries.collectAsStateWithLifecycle()
    val customer = customers.firstOrNull { it.id == customerId }

    if (customer == null) {
        V6MissingCustomer(onBack)
        return
    }

    val scope = rememberCoroutineScope()

    var filter by remember { mutableStateOf(MovementFilterV6.ALL) }
    var recentOnly by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf<LedgerEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<LedgerEntry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val thirtyDaysAgo = remember {
        System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
    }

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
                    MovementFilterV6.ALL -> true
                    MovementFilterV6.DEBTS -> it.type == EntryType.DEBT
                    MovementFilterV6.PAYMENTS -> it.type == EntryType.PAYMENT
                }
            }
            .filter { !recentOnly || it.createdAt >= thirtyDaysAgo }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    editEntry?.let { entry ->
        EditEntryDialog(
            entry = entry,
            onDismiss = { editEntry = null },
            onSave = { amount, bottles, bottlePrice, details ->
                scope.launch {
                    val result = vm.updateEntry(entry.id, amount, bottles, bottlePrice, details)
                    message = result.message
                    if (result.success) editEntry = null
                }
            }
        )
    }

    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("حذف الحركة؟") },
            text = {
                Text(
                    "سيتم فحص الرصيد الزمني للحساب قبل الحذف. " +
                        "إذا كان الحذف سيجعل تحصيلًا لاحقًا غير صالح فسيتم رفض العملية."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val result = vm.deleteEntry(entry.id)
                        message = result.message
                        if (result.success) deleteEntry = null
                    }
                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntry = null }) { Text("إلغاء") }
            }
        )
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("إدارة الحركة") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text("حسنًا") }
            }
        )
    }

    val currentBalance = vm.balance(customer)
    val totalDebts = customer.openingDebt + customerEntries
        .filter { it.type == EntryType.DEBT }.sumOf { it.amount }
    val totalPaid = customerEntries
        .filter { it.type == EntryType.PAYMENT }.sumOf { it.amount }

    Scaffold(topBar = { ScreenTopBar("إدارة الحركات", onBack) }) { padding ->
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
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StoredCustomerPhotoV211(
                                customerId = customer.id,
                                size = 46.dp
                            )
                            Column(
                                modifier = Modifier.weight(1f).padding(horizontal = 11.dp)
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
                            V6LedgerStat("إجمالي الدين", totalDebts, false, Modifier.weight(1f))
                            V6LedgerStat("إجمالي المدفوع", totalPaid, true, Modifier.weight(1f))
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MovementFilterV6.entries.forEach { option ->
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
                    Text("الحركات", style = MaterialTheme.typography.titleLarge)
                    Text(
                        visibleEntries.size.toString() + " حركة",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (visibleEntries.isEmpty()) {
                item { EmptyState("لا توجد حركات", "لا توجد عمليات مطابقة للفلاتر الحالية.") }
            } else {
                items(visibleEntries, key = { it.id }) { entry ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column {
                            TransactionRow(
                                entry = entry,
                                showBalance = balanceAfterByEntry[entry.id],
                                modifier = Modifier.padding(horizontal = 14.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { editEntry = entry }) {
                                    Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(17.dp))
                                    Text("تعديل", modifier = Modifier.padding(horizontal = 5.dp))
                                }
                                TextButton(onClick = { deleteEntry = entry }) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        null,
                                        modifier = Modifier.size(17.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        "حذف",
                                        modifier = Modifier.padding(horizontal = 5.dp),
                                        color = MaterialTheme.colorScheme.error
                                    )
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
private fun EditEntryDialog(
    entry: LedgerEntry,
    onDismiss: () -> Unit,
    onSave: (Long, Int?, Long?, String) -> Unit
) {
    var amount by remember(entry.id, entry.amount) { mutableStateOf(entry.amount.toString()) }
    var details by remember(entry.id, entry.details) { mutableStateOf(entry.details) }
    val parsedAmount = amount.toLongOrNull() ?: 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry.type == EntryType.DEBT) "تعديل الدين" else "تعديل التحصيل") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    "تاريخ الحركة: " + formatDate(entry.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter(Char::isDigit).take(12) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("المبلغ") },
                    suffix = { Text("د.ع") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                if (entry.type == EntryType.DEBT) {
                    OutlinedTextField(
                        value = details,
                        onValueChange = { details = it.take(160) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("المشتريات / الأدوية") },
                        maxLines = 3
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        "لن يُحفظ أي تعديل يفسد الرصيد التاريخي للحساب.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(parsedAmount, null, null, details) },
                enabled = parsedAmount > 0L
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun V6AccountHero(customer: Customer, balance: Long, hideAmounts: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (balance > 0) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StoredCustomerPhotoV211(
                    customerId = customer.id,
                    size = 50.dp
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(customer.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        customer.area.ifBlank { "بدون منطقة" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusChip(if (balance == 0L) "مسدد" else "مفتوح", balance == 0L)
            }
            Text("الدين الحالي", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (hideAmounts) "•••• د.ع" else formatMoney(balance),
                style = MaterialTheme.typography.headlineLarge,
                color = if (balance > 0) DebtRed else PaidGreen
            )
        }
    }
}

@Composable
private fun V6AccountAction(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    primary: Boolean,
    enabled: Boolean = true
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (primary && enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surface,
        border = if (primary && enabled) null
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = 10.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Text(title, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
}

@Composable
private fun V6ProfileInfo(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun V6LedgerStat(
    label: String,
    amount: Long,
    positive: Boolean,
    modifier: Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (positive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.errorContainer
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                formatMoney(amount),
                style = MaterialTheme.typography.titleSmall,
                color = if (positive) PaidGreen else DebtRed
            )
        }
    }
}

@Composable
private fun V6MissingCustomer(onBack: () -> Unit) {
    Scaffold(topBar = { ScreenTopBar("الزبون", onBack) }) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) { Text("تعذر العثور على الزبون.") }
    }
}

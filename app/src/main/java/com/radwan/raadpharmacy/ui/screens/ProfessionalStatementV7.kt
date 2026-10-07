package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.radwan.raadpharmacy.util.StatementDocumentRenderer
import com.radwan.raadpharmacy.util.StatementShare
import com.radwan.raadpharmacy.util.StatementSnapshot
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatMoney
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StatementScreenV7(
    vm: PharmacyLedgerViewModel,
    customerId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val allEntries by vm.entries.collectAsStateWithLifecycle()
    val customer = customers.firstOrNull { it.id == customerId }

    if (customer == null) {
        V7MissingCustomer(onBack)
        return
    }

    val entries = remember(allEntries, customerId) {
        allEntries.filter { it.customerId == customerId }
            .sortedByDescending { it.createdAt }
    }
    val totalDebts = remember(entries, customer.openingDebt) {
        customer.openingDebt + entries
            .asSequence()
            .filter { it.type == EntryType.DEBT }
            .sumOf { it.amount }
    }
    val totalPaid = remember(entries) {
        entries.asSequence()
            .filter { it.type == EntryType.PAYMENT }
            .sumOf { it.amount }
    }
    val balance = vm.balance(customer)
    val snapshot = remember(customer, entries, balance, totalDebts, totalPaid) {
        StatementSnapshot(
            customer = customer,
            entries = entries,
            currentBalance = balance,
            totalDebts = totalDebts,
            totalPaid = totalPaid
        )
    }

    var working by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun shareImage(whatsappOnly: Boolean) {
        if (working) return
        if (whatsappOnly && customer.phone.isNullOrBlank()) {
            statusMessage = "لا يوجد رقم هاتف محفوظ لهذا الزبون لفتح محادثته على WhatsApp."
            return
        }

        scope.launch {
            working = true
            val result = runCatching {
                val file = withContext(Dispatchers.IO) {
                    StatementDocumentRenderer.createPng(context, snapshot)
                }
                if (whatsappOnly) {
                    StatementShare.shareImageToWhatsappContact(
                        context = context,
                        file = file,
                        message = StatementDocumentRenderer.message(snapshot),
                        phone = customer.phone.orEmpty()
                    )
                } else {
                    StatementShare.shareImage(
                        context,
                        file,
                        StatementDocumentRenderer.message(snapshot),
                        false
                    )
                }
            }
            working = false
            if (result.isFailure) statusMessage = "تعذر فتح WhatsApp أو تجهيز صورة كشف الحساب."
        }
    }

    fun shareMessages() {
        if (working) return
        if (customer.phone.isNullOrBlank()) {
            statusMessage = "لا يوجد رقم هاتف محفوظ لهذا الزبون لإرسال الكشف عبر الرسائل."
            return
        }

        scope.launch {
            working = true
            val result = runCatching {
                val file = withContext(Dispatchers.IO) {
                    StatementDocumentRenderer.createPng(context, snapshot)
                }
                StatementShare.shareImageToMessages(
                    context = context,
                    file = file,
                    message = StatementDocumentRenderer.message(snapshot),
                    phone = customer.phone.orEmpty()
                )
            }
            working = false
            if (result.isFailure) {
                statusMessage = "تعذر فتح تطبيق الرسائل أو تجهيز صورة كشف الحساب."
            }
        }
    }

    statusMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { statusMessage = null },
            title = { Text("كشف الحساب") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { statusMessage = null }) {
                    Text("حسنًا")
                }
            }
        )
    }

    Scaffold(
        topBar = { ScreenTopBar("كشف الحساب", onBack) },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp
            ) {
                Button(
                    onClick = { shareImage(true) },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    if (working) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("جاري تجهيز الكشف...")
                    } else {
                        Icon(Icons.Rounded.Share, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            if (customer.phone.isNullOrBlank()) {
                                "مشاركة عبر WhatsApp"
                            } else {
                                "إرسال الكشف إلى " + customer.name
                            }
                        )
                    }
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CustomerAvatar(
                                customerId = customer.id,
                                size = 50.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "صيدلية رعد",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    "كشف حساب الدين • " + formatDate(snapshot.generatedAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        SoftDivider()

                        V7StatementLine("الزبون", customer.name)
                        if (!customer.phone.isNullOrBlank()) {
                            V7StatementLine("رقم الهاتف", customer.phone.orEmpty())
                        }
                        if (customer.area.isNotBlank()) {
                            V7StatementLine("المنطقة", customer.area)
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
                            V7SummaryCard(
                                "إجمالي الديون",
                                formatMoney(totalDebts),
                                false,
                                Modifier.weight(1f)
                            )
                            V7SummaryCard(
                                "إجمالي المدفوع",
                                formatMoney(totalPaid),
                                true,
                                Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "مشاركة كشف الحساب",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { shareImage(false) },
                        enabled = !working,
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Rounded.Image, null, modifier = Modifier.size(19.dp))
                        Text("مشاركة صورة", modifier = Modifier.padding(horizontal = 5.dp))
                    }
                    OutlinedButton(
                        onClick = ::shareMessages,
                        enabled = !working && !customer.phone.isNullOrBlank(),
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Rounded.Sms, null, modifier = Modifier.size(19.dp))
                        Text("رسالة SMS/MMS", modifier = Modifier.padding(horizontal = 5.dp))
                    }
                }
            }

            item {
                Text(
                    if (balance > 0L) {
                        "الحركات المرتبطة بالرصيد الحالي"
                    } else {
                        "آخر العمليات"
                    },
                    style = MaterialTheme.typography.titleLarge
                )
            }

            if (entries.isEmpty()) {
                item {
                    EmptyState(
                        "لا توجد عمليات",
                        "سيظهر سجل الحساب هنا بعد أول حركة."
                    )
                }
            } else {
                items(
                    if (balance > 0L) {
                        StatementDocumentRenderer.currentCycleEntries(snapshot).take(6)
                    } else {
                        entries.take(6)
                    },
                    key = { it.id }
                ) { entry ->
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
private fun V7SummaryCard(
    label: String,
    value: String,
    positive: Boolean,
    modifier: Modifier
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
                value,
                style = MaterialTheme.typography.titleSmall,
                color = if (positive) PaidGreen else DebtRed
            )
        }
    }
}

@Composable
private fun V7StatementLine(label: String, value: String) {
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
private fun V7MissingCustomer(onBack: () -> Unit) {
    Scaffold(topBar = { ScreenTopBar("الزبون", onBack) }) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text("تعذر العثور على الزبون.")
        }
    }
}

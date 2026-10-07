package com.radwan.raadpharmacy.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.util.CustomerSearch
import com.radwan.raadpharmacy.ui.components.CustomerAvatar
import com.radwan.raadpharmacy.ui.components.CustomerCard
import com.radwan.raadpharmacy.ui.components.EmptyState
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.theme.DebtRed
import com.radwan.raadpharmacy.util.daysSince
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatMoney
import com.radwan.raadpharmacy.util.normalizeIraqPhone

@Composable
fun SmartSearchScreenV4(
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
        CustomerSearch.rank(
            customers = customers,
            query = query,
            balance = vm::balance,
            recentAt = { customer ->
                vm.lastEntryFor(customer.id)?.createdAt ?: customer.createdAt
            }
        ).let { ranked ->
            if (query.isBlank()) ranked.take(10) else ranked
        }
    }

    Scaffold(topBar = { ScreenTopBar("البحث", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("الاسم، الهاتف، المنطقة أو العنوان") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    shape = CircleShape
                )
            }
            item {
                Text(
                    if (query.isBlank()) "آخر الحسابات" else "النتائج • " + results.size,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (results.isEmpty()) {
                item { EmptyState("لا توجد نتيجة", "جرّب جزءًا من الاسم أو رقم الهاتف أو المنطقة أو العنوان.") }
            } else {
                items(results, key = { it.id }) { customer ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CustomerCard(customer, vm.balance(customer), { onCustomer(customer.id) })
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { onDebt(customer.id) },
                                    modifier = Modifier.weight(1f),
                                    shape = MaterialTheme.shapes.medium
                                ) { Text("دين") }
                                OutlinedButton(
                                    onClick = { onPayment(customer.id) },
                                    enabled = vm.balance(customer) > 0,
                                    modifier = Modifier.weight(1f),
                                    shape = MaterialTheme.shapes.medium
                                ) { Text("تحصيل") }
                                Button(
                                    onClick = { onCustomer(customer.id) },
                                    modifier = Modifier.weight(1f),
                                    shape = MaterialTheme.shapes.medium
                                ) { Text("فتح") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FollowUpScreenV4(
    vm: PharmacyLedgerViewModel,
    onBack: () -> Unit,
    onCustomer: (String) -> Unit
) {
    val context = LocalContext.current
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("30+ يوم") }

    fun daysSincePayment(customer: Customer): Long {
        val payment = vm.lastPaymentFor(customer.id)
        return if (payment != null) daysSince(payment.createdAt) else daysSince(customer.createdAt)
    }

    val candidates = remember(customers, entries, filter) {
        val indebted = customers.filter { vm.balance(it) > 0 }
        when (filter) {
            "ديون قديمة" -> indebted.filter {
                vm.lastEntryFor(it.id)?.let { e -> daysSince(e.createdAt) >= 30 } == true
            }.sortedByDescending { vm.balance(it) }
            "أعلى الحسابات" -> indebted.sortedByDescending { vm.balance(it) }.take(15)
            "الكل" -> indebted.sortedByDescending { daysSincePayment(it) }
            else -> indebted.filter { daysSincePayment(it) >= 30 }.sortedByDescending { daysSincePayment(it) }
        }
    }

    Scaffold(topBar = { ScreenTopBar("المتابعة", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Column(Modifier.padding(horizontal = 11.dp)) {
                            Text("متابعة الحسابات", style = MaterialTheme.typography.titleLarge)
                            Text("ركّز على الحسابات الأقدم أو الأعلى مديونية.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("30+ يوم", "ديون قديمة", "أعلى الحسابات", "الكل").forEach { option ->
                        FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
                    }
                }
            }
            if (candidates.isEmpty()) {
                item { EmptyState("لا توجد حسابات تحتاج متابعة", "الحسابات الحالية لا تطابق الفلتر.") }
            } else {
                items(candidates, key = { it.id }) { customer ->
                    val lastPayment = vm.lastPaymentFor(customer.id)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CustomerAvatar(
                                    customerId = customer.id,
                                    size = 44.dp
                                )
                                Column(
                                    Modifier.weight(1f).padding(horizontal = 10.dp)
                                ) {
                                    Text(
                                        customer.name,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        customer.area.ifBlank { "بدون منطقة" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    formatMoney(vm.balance(customer)),
                                    color = DebtRed,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Text(
                                "آخر دفعة: " + (lastPayment?.let { formatDate(it.createdAt) + " • " + formatMoney(it.amount) } ?: "لا توجد"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "منذ آخر تحصيل: " + daysSincePayment(customer) + " يوم",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(
                                    onClick = {
                                        customer.phone?.let {
                                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + it)))
                                        }
                                    },
                                    enabled = !customer.phone.isNullOrBlank()
                                ) { Icon(Icons.Rounded.Call, "اتصال") }
                                IconButton(
                                    onClick = {
                                        customer.phone?.let {
                                            val p = normalizeIraqPhone(it).removePrefix("+")
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + p)))
                                            }
                                        }
                                    },
                                    enabled = !customer.phone.isNullOrBlank()
                                ) { Icon(Icons.Rounded.Chat, "WhatsApp") }
                                Button(
                                    onClick = { onCustomer(customer.id) },
                                    modifier = Modifier.weight(1f),
                                    shape = MaterialTheme.shapes.medium
                                ) { Text("فتح الحساب") }
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.DebtFollowupEngine
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.ui.components.CustomerAvatar
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.components.rememberLedgerFlingBehavior
import com.radwan.raadpharmacy.util.StatementDocumentRenderer
import com.radwan.raadpharmacy.util.StatementShare
import com.radwan.raadpharmacy.util.StatementSnapshot
import com.radwan.raadpharmacy.util.formatMoney
import com.radwan.raadpharmacy.util.validatedIraqiWhatsappPhone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class FollowupFilter(val title: String) {
    ALL("الكل"), DAYS_30("30 يومًا"), DAY_60("60 يومًا"), OLD("ديون قديمة"), REVIEW("مراجعة التاريخ")
}
private enum class FollowupSort(val title: String) {
    OLDEST("الأكثر تأخرًا"), HIGHEST("الأعلى دينًا"), LOWEST("الأقل دينًا"),
    NEWEST("الأحدث في المتابعة"), NAME("حسب الاسم")
}
private val orange = Color(0xFFB66A12)
private val deepRed = Color(0xFF9B2435)
private val mediumRed = Color(0xFFCA454A)
private val clockFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)

/**
 * Dedicated read-only follow-up center. All financial figures come from the
 * local ledger, which is already synchronized through the existing cloud flow.
 */
@Composable
fun SmartDebtFollowupV316(
    vm: PharmacyLedgerViewModel,
    onBack: () -> Unit,
    onCustomer: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val movements by vm.entries.collectAsStateWithLifecycle()
    val security by vm.securityState.collectAsStateWithLifecycle()

    var today by remember { mutableStateOf(LocalDate.now(DebtFollowupEngine.IRAQ_ZONE)) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(FollowupFilter.DAYS_30) }
    var sort by remember { mutableStateOf<FollowupSort?>(null) }
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var records by remember { mutableStateOf<List<DebtFollowupEngine.Account>>(emptyList()) }
    var sharing by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var missingPhoneFor by remember { mutableStateOf<String?>(null) }

    // Dates are derived, never stored; recompute when midnight passes in Baghdad.
    LaunchedEffect(Unit) {
        while (true) {
            today = LocalDate.now(DebtFollowupEngine.IRAQ_ZONE)
            val now = ZonedDateTime.now(DebtFollowupEngine.IRAQ_ZONE)
            val next = now.toLocalDate().plusDays(1).atStartOfDay(DebtFollowupEngine.IRAQ_ZONE)
            delay(ChronoUnit.MILLIS.between(now, next).coerceAtLeast(1000L) + 1000L)
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                today = LocalDate.now(DebtFollowupEngine.IRAQ_ZONE)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(customers, movements, today, refreshTick) {
        loading = true
        records = withContext(Dispatchers.Default) {
            DebtFollowupEngine.build(customers, movements, today)
        }
        loading = false
    }

    val overdue = remember(records) {
        records.filter {
            it.bucket != DebtFollowupEngine.Bucket.NOT_OVERDUE &&
                it.bucket != DebtFollowupEngine.Bucket.NEEDS_REVIEW
        }
    }
    val reviews = remember(records) { records.filter { it.bucket == DebtFollowupEngine.Bucket.NEEDS_REVIEW } }
    val amountOverdue = remember(overdue) { overdue.sumOf { it.overdueAmount } }
    val overdue30 = remember(overdue) { overdue.count { it.bucket == DebtFollowupEngine.Bucket.DAYS_30_TO_59 } }
    val overdue60 = remember(overdue) { overdue.count { it.bucket == DebtFollowupEngine.Bucket.DAY_60 } }
    val overdueOld = remember(overdue) { overdue.count { it.bucket == DebtFollowupEngine.Bucket.OVER_60 } }
    val shown = remember(records, filter, sort, query) {
        val source = when (filter) {
            FollowupFilter.ALL -> records.filter { it.bucket != DebtFollowupEngine.Bucket.NOT_OVERDUE }
            FollowupFilter.DAYS_30 -> overdue.filter { it.bucket == DebtFollowupEngine.Bucket.DAYS_30_TO_59 }
            FollowupFilter.DAY_60 -> overdue.filter { it.bucket == DebtFollowupEngine.Bucket.DAY_60 }
            FollowupFilter.OLD -> overdue.filter { it.bucket == DebtFollowupEngine.Bucket.OVER_60 }
            FollowupFilter.REVIEW -> reviews
        }
        val normalizedQuery = query.trim()
        val filtered = source.filter {
            normalizedQuery.isBlank() ||
                it.customer.name.contains(normalizedQuery, ignoreCase = true) ||
                it.customer.phone.orEmpty().contains(normalizedQuery)
        }
        when (sort) {
            null -> filtered
            FollowupSort.OLDEST -> filtered.sortedWith(compareByDescending<DebtFollowupEngine.Account> {
                it.ageDays ?: -1L
            }.thenByDescending { it.overdueAmount }.thenBy { it.customer.name })
            FollowupSort.HIGHEST -> filtered.sortedByDescending { it.balance }
            FollowupSort.LOWEST -> filtered.sortedBy { it.balance }
            FollowupSort.NEWEST -> filtered.sortedBy { it.oldestUnpaidAt ?: Long.MIN_VALUE }.asReversed()
            FollowupSort.NAME -> filtered.sortedBy { it.customer.name }
        }
    }

    fun share(id: String) {
        if (sharing != null) return
        val record = records.firstOrNull { it.customer.id == id } ?: return
        val customer = record.customer
        val digits = validatedIraqiWhatsappPhone(customer.phone.orEmpty())
        if (digits == null) {
            missingPhoneFor = id
            return
        }
        // Double taps are blocked synchronously before launching work.
        sharing = id
        scope.launch {
            val result = runCatching {
                val ownMovements = movements.filter { it.customerId == id }
                val debts = customer.openingDebt + ownMovements
                    .filter { it.type == EntryType.DEBT }.sumOf { it.amount }
                val payments = ownMovements.filter { it.type == EntryType.PAYMENT }.sumOf { it.amount }
                val snapshot = StatementSnapshot(
                    customer = customer,
                    entries = ownMovements,
                    currentBalance = vm.balance(customer),
                    totalDebts = debts,
                    totalPaid = payments
                )
                val file = withContext(Dispatchers.Default) {
                    StatementDocumentRenderer.createPng(context, snapshot)
                }
                StatementShare.shareImageToWhatsappContact(
                    context = context,
                    file = file,
                    message = StatementDocumentRenderer.message(snapshot),
                    phone = digits
                )
            }
            sharing = null
            statusMessage = if (result.isSuccess)
                "فُتحت واجهة مشاركة كشف الحساب. لم يتم تأكيد إرسال الرسالة من واتساب."
            else "تعذر فتح مشاركة كشف الحساب. تحقق من رقم الهاتف ووجود واتساب."
        }
    }

    statusMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { statusMessage = null },
            title = { Text("مشاركة كشف الحساب") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { statusMessage = null }) { Text("حسنًا") } }
        )
    }
    missingPhoneFor?.let { id ->
        AlertDialog(
            onDismissRequest = { missingPhoneFor = null },
            title = { Text("رقم الهاتف غير صالح") },
            text = { Text("يلزم رقم موبايل عراقي صحيح لهذا الزبون قبل فتح مشاركة واتساب.") },
            confirmButton = {
                TextButton(onClick = { missingPhoneFor = null; onCustomer(id) }) { Text("عرض بيانات الزبون") }
            },
            dismissButton = { TextButton(onClick = { missingPhoneFor = null }) { Text("إلغاء") } }
        )
    }

    Scaffold(topBar = { ScreenTopBar("متابعة الحسابات", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            flingBehavior = rememberLedgerFlingBehavior(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(48.dp).background(
                                MaterialTheme.colorScheme.surface, CircleShape
                            ), contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("مركز المتابعة الذكي", style = MaterialTheme.typography.titleLarge)
                        }
                        IconButton(onClick = {
                            today = LocalDate.now(DebtFollowupEngine.IRAQ_ZONE)
                            refreshTick++ // Recalculate without modifying saved ledger data.
                        }) { Icon(Icons.Rounded.Refresh, "تحديث") }
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Rounded.Search, "بحث")
                        }
                    }
                }
            }
            if (showSearch) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("الاسم أو رقم الهاتف") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
            item {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        Text("ملخص المتأخرات", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (security.hideAmounts) "•••• د.ع" else formatMoney(amountOverdue),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("المبالغ المستحقة منذ 30 يومًا فأكثر فقط",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium)
                        HorizontalDivider()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FollowupStat("الحسابات المتأخرة", overdue.size.toString(), Modifier.weight(1f))
                            FollowupStat("30–59 يومًا", overdue30.toString(), Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FollowupStat("60 يومًا", overdue60.toString(), Modifier.weight(1f))
                            FollowupStat("أكثر من 60 يومًا", overdueOld.toString(), Modifier.weight(1f))
                        }
                        if (reviews.isNotEmpty()) {
                            Text("${reviews.size} حسابًا يحتاج مراجعة تاريخ الدين؛ غير محتسب ضمن إجمالي المتأخرات.",
                                color = orange, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    FollowupFilter.entries.filter { it != FollowupFilter.REVIEW || reviews.isNotEmpty() }
                        .forEach { option ->
                            val count = when (option) {
                                FollowupFilter.ALL -> overdue.size + reviews.size
                                FollowupFilter.DAYS_30 -> overdue30
                                FollowupFilter.DAY_60 -> overdue60
                                FollowupFilter.OLD -> overdueOld
                                FollowupFilter.REVIEW -> reviews.size
                            }
                            FilterChip(
                                selected = filter == option,
                                onClick = { filter = option },
                                label = { Text("${option.title} ($count)") }
                            )
                        }
                }
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    Text("فرز:", style = MaterialTheme.typography.labelMedium)
                    FollowupSort.entries.forEach { option ->
                        FilterChip(
                            selected = sort == option,
                            onClick = { sort = option },
                            label = { Text(option.title) }
                        )
                    }
                }
            }
            if (loading) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(20.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("جارٍ حساب أعمار الديون...")
                    }
                }
            } else if (shown.isEmpty()) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.fillMaxWidth().padding(30.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Text(if (query.isNotBlank()) "لا توجد نتائج تطابق البحث"
                                else "ممتاز! لا توجد حسابات متأخرة حاليًا",
                                style = MaterialTheme.typography.titleMedium)
                            Text("تُحدّث القائمة تلقائيًا مع الحركات والمزامنة. تُعرض آخر بيانات متاحة عند انقطاع الإنترنت.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                items(shown, key = { it.customer.id }) { item ->
                    FollowupAccountCard(
                        record = item,
                        hideAmounts = security.hideAmounts,
                        isSharing = sharing == item.customer.id,
                        isBusy = sharing != null,
                        onShare = { share(item.customer.id) },
                        onOpen = { onCustomer(item.customer.id) }
                    )
                }
            }
            item {
                Text("توزيع التحصيلات: الأقدم أولًا (FIFO) • الحسابات مجهولة التاريخ تحتاج مراجعة • لا تُعدّل الحركات أو الأرصدة.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(vertical = 9.dp)
                )
            }
        }
    }
}

@Composable
private fun FollowupStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FollowupAccountCard(
    record: DebtFollowupEngine.Account,
    hideAmounts: Boolean,
    isSharing: Boolean,
    isBusy: Boolean,
    onShare: () -> Unit,
    onOpen: () -> Unit
) {
    val shade = when (record.bucket) {
        DebtFollowupEngine.Bucket.DAYS_30_TO_59 -> orange
        DebtFollowupEngine.Bucket.DAY_60 -> mediumRed
        DebtFollowupEngine.Bucket.OVER_60 -> deepRed
        DebtFollowupEngine.Bucket.NEEDS_REVIEW -> orange
        DebtFollowupEngine.Bucket.NOT_OVERDUE -> MaterialTheme.colorScheme.primary
    }
    val stateText = when (record.bucket) {
        DebtFollowupEngine.Bucket.OVER_60 -> "دين قديم — ${record.ageDays} يومًا"
        DebtFollowupEngine.Bucket.NEEDS_REVIEW -> "التاريخ يحتاج مراجعة"
        else -> "متأخر ${record.ageDays} يومًا"
    }
    val dateText = record.oldestUnpaidAt?.let {
        runCatching {
            Instant.ofEpochMilli(it).atZone(DebtFollowupEngine.IRAQ_ZONE)
                .toLocalDate().format(clockFormat)
        }.getOrNull()
    } ?: "غير مؤكد"
    val lastPaymentText = record.lastPaymentAt?.let {
        Instant.ofEpochMilli(it).atZone(DebtFollowupEngine.IRAQ_ZONE)
            .toLocalDate().format(clockFormat)
    } ?: "لا يوجد"

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CustomerAvatar(customerId = record.customer.id, size = 44.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(record.customer.name, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(record.customer.phone.orEmpty().ifEmpty { "لا يوجد رقم هاتف" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(
                    color = shade.copy(alpha = 0.10f),
                    shape = CircleShape
                ) {
                    Text(stateText, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = shade, style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("الرصيد الحالي", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (hideAmounts) "•••• د.ع" else formatMoney(record.balance),
                        style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("المبلغ المتأخر", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (record.hasUnknownDates) "يحتاج مراجعة"
                        else if (hideAmounts) "•••• د.ع" else formatMoney(record.overdueAmount),
                        style = MaterialTheme.typography.titleMedium, color = shade)
                }
            }
            Text("أقدم دين غير مسدد: $dateText   •   آخر تحصيل: $lastPaymentText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onShare,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isSharing) CircularProgressIndicator(
                        modifier = Modifier.size(16.dp), strokeWidth = 2.dp
                    ) else Icon(Icons.Rounded.Send, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("إرسال كشف الحساب", maxLines = 1)
                }
                OutlinedButton(onClick = onOpen) {
                    Text("عرض الحساب")
                    Icon(Icons.Rounded.ChevronLeft, null, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.data.AdvancedReportSnapshot
import com.radwan.raadpharmacy.data.AreaDebtSummary
import com.radwan.raadpharmacy.data.CustomerDebtSummary
import com.radwan.raadpharmacy.data.DailyMovementSummary
import com.radwan.raadpharmacy.data.ReportPeriodV11
import com.radwan.raadpharmacy.ui.components.CustomerAvatar
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.components.SectionTitle
import com.radwan.raadpharmacy.ui.theme.DebtRed
import com.radwan.raadpharmacy.ui.theme.PaidGreen
import com.radwan.raadpharmacy.util.formatMoney
import java.util.Locale

@Composable
fun ReportsScreenV11(
    vm: PharmacyLedgerViewModel,
    onCustomer: (String) -> Unit
) {
    val report by vm.advancedReport.collectAsStateWithLifecycle()
    val security by vm.securityState.collectAsStateWithLifecycle()
    var selectedPeriod by rememberSaveable { mutableStateOf(ReportPeriodV11.MONTH) }

    LaunchedEffect(selectedPeriod) {
        vm.loadAdvancedReport(selectedPeriod)
    }

    Scaffold(topBar = { ScreenTopBar("التقارير") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 6.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReportPeriodV11.entries.forEach { option ->
                        FilterChip(
                            selected = selectedPeriod == option,
                            onClick = { selectedPeriod = option },
                            label = { Text(option.label) }
                        )
                    }
                }
            }

            if (report.loading) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Text("جاري تجهيز مؤشرات التقرير...")
                        }
                    }
                }
            }

            report.errorMessage?.let { error ->
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            error,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            item {
                V11NetHero(
                    report = report,
                    hideAmounts = security.hideAmounts
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    V11Metric(
                        "ديون الفترة",
                        money(report.debts, security.hideAmounts),
                        Icons.Rounded.ReceiptLong,
                        DebtRed,
                        Modifier.weight(1f)
                    )
                    V11Metric(
                        "التحصيلات",
                        money(report.collections, security.hideAmounts),
                        Icons.Rounded.Payments,
                        PaidGreen,
                        Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    V11Metric(
                        "الدين الحالي",
                        money(report.totalDebt, security.hideAmounts),
                        Icons.Rounded.AccountBalanceWallet,
                        DebtRed,
                        Modifier.weight(1f)
                    )
                    V11Metric(
                        "معدل التحصيل",
                        percent(report.collectionRatePercent),
                        Icons.Rounded.TrendingUp,
                        PaidGreen,
                        Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    V11Metric(
                        "الحسابات المفتوحة",
                        report.openAccounts.toString(),
                        Icons.Rounded.AccountBalanceWallet,
                        MaterialTheme.colorScheme.primary,
                        Modifier.weight(1f)
                    )
                    V11Metric(
                        "متوسط الدين",
                        money(report.averageOpenDebt, security.hideAmounts),
                        Icons.Rounded.Groups,
                        MaterialTheme.colorScheme.primary,
                        Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    V11Metric(
                        "كل الزبائن",
                        report.totalCustomers.toString(),
                        Icons.Rounded.People,
                        MaterialTheme.colorScheme.primary,
                        Modifier.weight(1f)
                    )
                    V11Metric(
                        "زبائن جدد",
                        report.newCustomers.toString(),
                        Icons.Rounded.PersonAdd,
                        MaterialTheme.colorScheme.primary,
                        Modifier.weight(1f)
                    )
                }
            }

            item {
                V11CollectionRateCard(report)
            }

            item { SectionTitle("اتجاه الديون والتحصيلات") }

            item {
                V11TrendCard(
                    values = report.dailyMovement,
                    period = selectedPeriod,
                    hideAmounts = security.hideAmounts
                )
            }

            item { SectionTitle("أعلى المناطق مديونية") }

            if (report.topAreas.isEmpty()) {
                item { V11EmptyInsight("لا توجد مناطق عليها أرصدة مفتوحة.") }
            } else {
                item {
                    V11AreasCard(
                        items = report.topAreas,
                        hideAmounts = security.hideAmounts
                    )
                }
            }

            item { SectionTitle("أعلى الزبائن مديونية") }

            if (report.topCustomers.isEmpty()) {
                item { V11EmptyInsight("لا توجد حسابات مفتوحة حاليًا.") }
            } else {
                item {
                    V11CustomersCard(
                        items = report.topCustomers,
                        hideAmounts = security.hideAmounts,
                        onCustomer = onCustomer
                    )
                }
            }
        }
    }
}

@Composable
private fun V11NetHero(
    report: AdvancedReportSnapshot,
    hideAmounts: Boolean
) {
    val positiveDebt = report.netMovement > 0L

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
                "صافي حركة " + report.period.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                money(report.netMovement, hideAmounts),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = if (positiveDebt) DebtRed else PaidGreen
            )
            Text(
                when {
                    report.debts == 0L && report.collections == 0L ->
                        "لا توجد حركات مالية في هذه الفترة."
                    positiveDebt ->
                        "الديون الجديدة أعلى من التحصيلات خلال الفترة."
                    report.netMovement < 0L ->
                        "التحصيلات أعلى من الديون الجديدة خلال الفترة."
                    else ->
                        "الديون والتحصيلات متساوية خلال الفترة."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun V11Metric(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    modifier: Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun V11CollectionRateCard(report: AdvancedReportSnapshot) {
    val fraction = (report.collectionRatePercent / 100.0)
        .toFloat()
        .coerceIn(0f, 1f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("معدل التحصيل مقابل ديون الفترة")
                Text(
                    percent(report.collectionRatePercent),
                    fontWeight = FontWeight.Bold,
                    color = PaidGreen
                )
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = PaidGreen,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Text(
                if (report.collectionRatePercent > 100.0)
                    "قد يتجاوز 100% عند تحصيل ديون قديمة مسجلة قبل الفترة."
                else
                    "النسبة = تحصيلات الفترة ÷ الديون الجديدة في الفترة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun V11TrendCard(
    values: List<DailyMovementSummary>,
    period: ReportPeriodV11,
    hideAmounts: Boolean
) {
    val visible = remember(values, period) {
        when (period) {
            ReportPeriodV11.TODAY -> values
            ReportPeriodV11.WEEK -> values
            ReportPeriodV11.MONTH -> values.chunked(3).map { group ->
                DailyMovementSummary(
                    dayKey = group.firstOrNull()?.dayKey.orEmpty(),
                    debts = group.sumOf { it.debts },
                    collections = group.sumOf { it.collections }
                )
            }
        }
    }
    val maxValue = maxOf(
        visible.maxOfOrNull { it.debts } ?: 0L,
        visible.maxOfOrNull { it.collections } ?: 0L,
        1L
    )
    val debtColor = DebtRed
    val paidColor = PaidGreen

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                V11LegendDot(debtColor, "ديون")
                V11LegendDot(paidColor, "تحصيل")
            }

            Canvas(
                modifier = Modifier.fillMaxWidth().height(150.dp)
            ) {
                if (visible.isEmpty()) return@Canvas

                val groups = visible.size.coerceAtLeast(1)
                val groupWidth = size.width / groups.toFloat()
                val barWidth = (groupWidth * 0.24f).coerceAtLeast(3f)
                val chartHeight = size.height * 0.90f

                drawLine(
                    color = Color.LightGray,
                    start = Offset(0f, chartHeight),
                    end = Offset(size.width, chartHeight),
                    strokeWidth = 1.5f
                )

                visible.forEachIndexed { index, item ->
                    val center = groupWidth * index + groupWidth / 2f
                    val debtHeight = chartHeight * (item.debts.toFloat() / maxValue.toFloat())
                    val paidHeight = chartHeight * (item.collections.toFloat() / maxValue.toFloat())

                    drawRoundRect(
                        color = debtColor,
                        topLeft = Offset(center - barWidth - 2f, chartHeight - debtHeight),
                        size = Size(barWidth, debtHeight),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                    drawRoundRect(
                        color = paidColor,
                        topLeft = Offset(center + 2f, chartHeight - paidHeight),
                        size = Size(barWidth, paidHeight),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }
            }

            if (visible.isNotEmpty()) {
                val first = visible.first().dayKey.takeLast(5)
                val last = visible.last().dayKey.takeLast(5)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(first, style = MaterialTheme.typography.labelSmall)
                    Text(last, style = MaterialTheme.typography.labelSmall)
                }
            }

            val total = values.sumOf { it.debts + it.collections }
            if (total == 0L) {
                Text(
                    "لا توجد حركات في الفترة المختارة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (!hideAmounts) {
                Text(
                    "إجمالي الحركة المعروضة: " + formatMoney(total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun V11LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            modifier = Modifier.size(9.dp),
            shape = CircleShape,
            color = color
        ) {}
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun V11AreasCard(
    items: List<AreaDebtSummary>,
    hideAmounts: Boolean
) {
    val max = (items.maxOfOrNull { it.balance } ?: 1L).coerceAtLeast(1L)

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
            items.forEachIndexed { index, item ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            (index + 1).toString() + ". " + item.area,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            money(item.balance, hideAmounts),
                            style = MaterialTheme.typography.labelLarge,
                            color = DebtRed
                        )
                    }
                    LinearProgressIndicator(
                        progress = { item.balance.toFloat() / max.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = DebtRed,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun V11CustomersCard(
    items: List<CustomerDebtSummary>,
    hideAmounts: Boolean,
    onCustomer: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCustomer(item.customerId) }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CustomerAvatar(
                        customerId = item.customerId,
                        size = 36.dp
                    )
                    Text(
                        "#" + (index + 1).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            item.area,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        money(item.balance, hideAmounts),
                        style = MaterialTheme.typography.labelLarge,
                        color = DebtRed
                    )
                }
            }
        }
    }
}

@Composable
private fun V11EmptyInsight(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun money(value: Long, hidden: Boolean): String =
    if (hidden) "•••• د.ع" else formatMoney(value)

private fun percent(value: Double): String =
    String.format(Locale.US, "%.0f%%", value.coerceAtLeast(0.0))

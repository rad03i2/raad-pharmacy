package com.radwan.raadpharmacy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radwan.raadpharmacy.util.financialQuickAmounts
import com.radwan.raadpharmacy.util.formatMoney

@Composable
internal fun CompactFinanceHeader(customerId: String, name: String, balance: Long) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CustomerAvatar(customerId, size = 38.dp)
            Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Column(horizontalAlignment = Alignment.End) {
                Text("الدين الحالي", style = MaterialTheme.typography.labelSmall)
                Text(formatMoney(balance), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun FinancialQuickAmounts(enabled: Boolean, onAdd: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("مبالغ جاهزة • كل ضغطة تضيف للمبلغ • د.ع", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        financialQuickAmounts.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { value ->
                    Surface(onClick = { onAdd(value) }, enabled = enabled,
                        modifier = Modifier.weight(1f).height(42.dp), shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))) {
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            Text(value.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, softWrap = false)
                        }
                    }
                }
                repeat(5 - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun CompactBalanceSummary(before: Long, movement: Long, after: Long, payment: Boolean) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(10.dp, 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(formatMoney(before) + (if (payment) " − " else " + ") + formatMoney(movement),
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (payment) "المتبقي" else "الرصيد بعد التسجيل", style = MaterialTheme.typography.labelMedium)
                Text(formatMoney(after), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

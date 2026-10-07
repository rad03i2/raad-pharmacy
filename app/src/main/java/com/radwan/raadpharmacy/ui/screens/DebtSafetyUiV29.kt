package com.radwan.raadpharmacy.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.radwan.raadpharmacy.data.DebtAnomalyWarning
import com.radwan.raadpharmacy.ui.theme.DebtRed
import com.radwan.raadpharmacy.ui.theme.PaidGreen
import com.radwan.raadpharmacy.util.formatMoney

@Composable
internal fun V29DebtMicrophoneButton(
    listening: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "debt-mic-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(750),
            repeatMode = RepeatMode.Reverse
        ),
        label = "debt-mic-pulse-progress"
    )

    Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center
    ) {
        if (listening) {
            val primary = MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.size(46.dp)) {
                drawCircle(
                    color = primary.copy(alpha = 0.12f + (0.12f * pulse)),
                    radius = (size.minDimension / 2f) * pulse
                )
                drawCircle(
                    color = primary.copy(alpha = 0.42f),
                    radius = size.minDimension / 2f - 2.dp.toPx(),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )
            }
        }

        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (listening) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            IconButton(
                onClick = onClick,
                enabled = enabled
            ) {
                Icon(
                    imageVector = if (listening) {
                        Icons.Rounded.MicOff
                    } else {
                        Icons.Rounded.Mic
                    },
                    contentDescription = if (listening) {
                        "إيقاف الاستماع"
                    } else {
                        "إدخال المبلغ بالصوت"
                    },
                    tint = if (listening) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
internal fun V29VoiceStatus(
    listening: Boolean,
    preparing: Boolean = false,
    processing: Boolean = false,
    transcript: String,
    feedback: String?,
    error: String?
) {
    val isError = error != null
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = when {
            isError -> MaterialTheme.colorScheme.errorContainer
            listening -> MaterialTheme.colorScheme.primaryContainer
            feedback != null -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            1.dp,
            if (isError) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (listening) {
                Text(
                    when {
                        preparing -> "جارٍ تجهيز الميكروفون..."
                        processing -> "جارٍ تحديد المبلغ..."
                        else -> "جارٍ الاستماع..."
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            if (transcript.isNotBlank()) {
                Text(
                    "سمعت: " + transcript.map { ch ->
                        if (ch.isDigit()) Character.getNumericValue(ch).digitToChar() else ch
                    }.joinToString(""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            feedback?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaidGreen,
                    fontWeight = FontWeight.Medium
                )
            }

            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
internal fun V29DuplicateDebtDialog(
    customerName: String,
    amount: Long,
    secondsAgo: Long,
    onCancel: () -> Unit,
    onForceSave: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 10.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                V29WarningIcon()

                Text(
                    "يبدو أن هذا الدين تم تسجيله قبل لحظات",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                V29ProtectionSummary(
                    firstLabel = "المبلغ",
                    firstValue = formatMoney(amount),
                    secondLabel = "الزبون",
                    secondValue = customerName
                )

                Text(
                    if (secondsAgo <= 1L) {
                        "تم تسجيل عملية بنفس المبلغ لهذا الزبون قبل لحظات."
                    } else {
                        "تم تسجيل عملية بنفس المبلغ لهذا الزبون قبل " +
                            secondsAgo.toString() + " ثوانٍ."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("إلغاء", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onForceSave,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("تسجيله على أي حال")
                }
            }
        }
    }
}

@Composable
internal fun V29DebtAnomalyDialog(
    customerName: String,
    warning: DebtAnomalyWarning,
    onReview: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onReview) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 10.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                V29WarningIcon()

                Text(
                    "المبلغ أكبر بكثير من المعتاد",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    customerName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                V29ProtectionSummary(
                    firstLabel = "المبلغ الذي أدخلته",
                    firstValue = formatMoney(warning.enteredAmount),
                    secondLabel = "المبلغ المعتاد تقريبًا",
                    secondValue = formatMoney(warning.typicalAmount)
                )

                Text(
                    "تمت المقارنة بآخر " + warning.comparisonCount.toString() +
                        " عمليات دين. هل أنت متأكد من تسجيل هذا المبلغ؟",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Button(
                    onClick = onReview,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("مراجعة المبلغ", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("نعم، تسجيل الدين", color = DebtRed)
                }
            }
        }
    }
}

@Composable
private fun V29WarningIcon() {
    Surface(
        modifier = Modifier.size(62.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(31.dp)
            )
        }
    }
}

@Composable
private fun V29ProtectionSummary(
    firstLabel: String,
    firstValue: String,
    secondLabel: String,
    secondValue: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    firstLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(firstValue, fontWeight = FontWeight.Bold)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    secondLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(secondValue, fontWeight = FontWeight.Medium)
            }
        }
    }
}

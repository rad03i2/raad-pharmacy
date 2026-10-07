package com.radwan.raadpharmacy.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.notifications.ReminderFrequency
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatTime

@Composable
fun SmartReminderSettingsCardV10(vm: PharmacyLedgerViewModel) {
    val context = LocalContext.current
    val settings by vm.reminderSettings.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.setReminderEnabled(true)
            message = "تم تفعيل إشعارات المتابعة الذكية."
        } else {
            vm.setReminderEnabled(false)
            message = "لم يتم منح إذن الإشعارات، لذلك بقيت المتابعة متوقفة."
        }
    }

    LaunchedEffect(Unit) {
        vm.refreshReminderSettings()
    }

    fun requestEnable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            vm.setReminderEnabled(false)
            message = "إشعارات التطبيق معطلة من إعدادات Android."
        } else {
            vm.setReminderEnabled(true)
        }
    }

    message?.let { value ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("المتابعة الذكية") },
            text = { Text(value) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text("حسنًا") }
            }
        )
    }

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
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (settings.enabled) Icons.Rounded.NotificationsActive
                    else Icons.Rounded.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("إشعارات المتابعة الذكية", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "ينبهك بالحسابات القديمة وأعلى المديونيات دون إغراقك بالتنبيهات.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { enabled ->
                        if (enabled) requestEnable() else vm.setReminderEnabled(false)
                    }
                )
            }

            Text("بداية المتابعة", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(7, 15, 30).forEach { days ->
                    FilterChip(
                        selected = settings.minimumAgeDays == days,
                        onClick = { vm.setReminderMinimumAge(days) },
                        label = { Text(days.toString() + " يوم") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text("تكرار الفحص", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReminderFrequency.entries.forEach { frequency ->
                    FilterChip(
                        selected = settings.frequency == frequency,
                        onClick = { vm.setReminderFrequency(frequency) },
                        label = { Text(frequencyLabel(frequency)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (settings.lastCheckAt > 0L) {
                Text(
                    "آخر فحص: " + formatDate(settings.lastCheckAt) +
                        " • " + formatTime(settings.lastCheckAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "لم يتم إجراء فحص بعد.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedButton(
                onClick = {
                    if (!settings.enabled) {
                        message = "فعّل إشعارات المتابعة أولًا."
                    } else {
                        vm.runReminderCheckNow()
                        message = "تم إرسال طلب فحص الآن. سيظهر تنبيه فقط إذا وُجد حساب يحتاج متابعة."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = settings.enabled
            ) {
                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                Text("فحص الآن", modifier = Modifier.padding(horizontal = 6.dp))
            }

            Text(
                "التطبيق يصنف المتابعة إلى 7 / 15 / 30 يومًا، ويعطي أولوية للحسابات الأقدم والأعلى مديونية. الحد الأقصى تنبيهان فرديان في كل فحص.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun frequencyLabel(frequency: ReminderFrequency): String =
    when (frequency) {
        ReminderFrequency.DAILY -> "يومي"
        ReminderFrequency.EVERY_THREE_DAYS -> "كل 3 أيام"
        ReminderFrequency.WEEKLY -> "أسبوعي"
    }

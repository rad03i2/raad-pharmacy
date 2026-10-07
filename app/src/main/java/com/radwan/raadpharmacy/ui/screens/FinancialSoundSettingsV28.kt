package com.radwan.raadpharmacy.ui.screens

import android.Manifest
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.notifications.FinancialFeedbackSettings
import com.radwan.raadpharmacy.notifications.FinancialOperationFeedback
import com.radwan.raadpharmacy.notifications.LedgerNotificationChannels
import com.radwan.raadpharmacy.notifications.NotificationSoundPreset
import com.radwan.raadpharmacy.notifications.OperationSoundPreset

@Composable
fun FinancialSoundSettingsCardV28(vm: PharmacyLedgerViewModel) {
    val context = LocalContext.current
    val settings = remember { vm.financialFeedbackSettings() }
    var message by remember { mutableStateOf<String?>(null) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            FinancialOperationFeedback.postPreviewNotification(context)
            message = "تم إرسال إشعار تجريبي."
        } else {
            message = "السماح بإشعارات التطبيق مطلوب."
        }
    }

    DisposableEffect(Unit) {
        onDispose { FinancialOperationFeedback.stopPreviewSound() }
    }

    FinancialSoundSettingsContent(
        settings = settings,
        onPreviewOperation = vm::previewOperationSound,
        onPreviewNotification = vm::previewNotificationSound,
        onOpenNotificationSettings = {
            runCatching {
                context.startActivity(LedgerNotificationChannels.settingsIntent(context))
            }.onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                    )
                }.onFailure {
                    message = "افتح إعدادات الهاتف ثم إشعارات دفتر صيدلية رعد."
                }
            }
        },
        onTestNotification = {
            if (FinancialOperationFeedback.canPostNotifications(context)) {
                FinancialOperationFeedback.postPreviewNotification(context)
                message = "تم إرسال إشعار تجريبي بصوت التنبيه السحابي."
            } else if (
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                message = "إشعارات التطبيق مغلقة في إعدادات الهاتف."
            }
        },
        notificationMessage = message
    )
}

@Composable
internal fun FinancialSoundSettingsContent(
    settings: FinancialFeedbackSettings,
    onPreviewOperation: (OperationSoundPreset) -> Unit,
    onPreviewNotification: (NotificationSoundPreset) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onTestNotification: () -> Unit,
    notificationMessage: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FixedSoundCardV330(
            title = "صوت اكتمال العملية",
            sourceTitle = settings.operationSound.title,
            description = "يعمل عند نجاح تسجيل الدين أو التحصيل. يعتمد التطبيق هذا الصوت فقط للعمليات.",
            icon = { Icon(Icons.Rounded.VolumeUp, null, tint = MaterialTheme.colorScheme.primary) },
            onPreview = { onPreviewOperation(OperationSoundPreset.PIXABAY_OPERATION) }
        )

        FixedSoundCardV330(
            title = "صوت التنبيه السحابي",
            sourceTitle = settings.notificationSound.title,
            description = "نفس الصوت للتنبيه الداخلي وللإشعار الخارجي القادم من هاتف آخر.",
            icon = {
                Icon(
                    Icons.Rounded.NotificationsActive,
                    null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            onPreview = { onPreviewNotification(NotificationSoundPreset.PIXABAY_NOTIFICATION) }
        ) {
            OutlinedButton(
                onClick = onTestNotification,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("تجربة إشعار خارجي")
            }
            OutlinedButton(
                onClick = onOpenNotificationSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("إعدادات إشعارات الهاتف")
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                "تم اعتماد صوتين فقط من Pixabay: صوت لاكتمال العملية وصوت للتنبيه السحابي. لا توجد نغمات إضافية.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        notificationMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun FixedSoundCardV330(
    title: String,
    sourceTitle: String,
    description: String,
    icon: @Composable () -> Unit,
    onPreview: () -> Unit,
    extra: @Composable () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        icon()
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        sourceTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = onPreview,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                Text("تجربة الصوت")
            }

            extra()
        }
    }
}

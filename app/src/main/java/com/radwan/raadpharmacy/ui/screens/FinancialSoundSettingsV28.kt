package com.radwan.raadpharmacy.ui.screens

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    var settings by remember { mutableStateOf(vm.financialFeedbackSettings()) }
    var message by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            FinancialOperationFeedback.postPreviewNotification(context)
            message = "تم إرسال إشعار تجريبي. إذا لم يظهر منبثقًا، افتح إعدادات التنبيه المنبثق."
        } else message = "السماح بإشعارات التطبيق مطلوب لعرض التنبيه المنبثق."
    }
    DisposableEffect(Unit) { onDispose { FinancialOperationFeedback.stopPreviewSound() } }

    FinancialSoundSettingsContent(
        settings = settings,
        onOperationSelected = { preset ->
            vm.setOperationSound(preset)
            settings = settings.copy(operationSound = preset)
        },
        onNotificationSelected = { preset ->
            vm.setNotificationSound(preset)
            settings = settings.copy(notificationSound = preset)
        },
        onPreviewOperation = vm::previewOperationSound,
        onPreviewNotification = vm::previewNotificationSound,
        onOpenNotificationSettings = {
            runCatching { context.startActivity(LedgerNotificationChannels.settingsIntent(context)) }
                .onFailure {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        })
                    }.onFailure { message = "افتح إعدادات الهاتف ثم إشعارات دفتر صيدلية رعد." }
                }
        },
        onTestNotification = {
            if (FinancialOperationFeedback.canPostNotifications(context)) {
                FinancialOperationFeedback.postPreviewNotification(context)
                message = "تم إرسال إشعار تجريبي بالصوت المختار."
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else message = "إشعارات التطبيق مغلقة في إعدادات الهاتف. افتح إعدادات التنبيه المنبثق لتفعيلها."
        },
        notificationMessage = message
    )
}

@Composable
internal fun FinancialSoundSettingsContent(
    settings: FinancialFeedbackSettings,
    onOperationSelected: (OperationSoundPreset) -> Unit,
    onNotificationSelected: (NotificationSoundPreset) -> Unit,
    onPreviewOperation: (OperationSoundPreset) -> Unit,
    onPreviewNotification: (NotificationSoundPreset) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onTestNotification: () -> Unit,
    notificationMessage: String? = null
) {
    var expandedGroup by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SoundGroupCardV28(
            title = "صوت نجاح العملية",
            selectedTitle = settings.operationSound.title,
            description = "يعمل فور نجاح تسجيل الدين أو التحصيل أو التسديد الكامل.",
            expanded = expandedGroup == "operation",
            onToggle = { expandedGroup = if (expandedGroup == "operation") null else "operation" },
            icon = { Icon(Icons.Rounded.VolumeUp, null, tint = MaterialTheme.colorScheme.primary) }
        ) {
            OperationSoundPreset.entries.forEach { preset ->
                SoundChoiceRowV28(preset.title, preset.description, settings.operationSound == preset,
                    onSelect = { onOperationSelected(preset) }, onPreview = { onPreviewOperation(preset) })
            }
        }
        SoundGroupCardV28(
            title = "صوت إشعار الهاتف",
            selectedTitle = settings.notificationSound.title,
            description = "يُستخدم لتأكيد العمليات ومتابعة الديون. تنبيه تأكيد العملية يصل بعد ثانيتين.",
            expanded = expandedGroup == "notification",
            onToggle = { expandedGroup = if (expandedGroup == "notification") null else "notification" },
            icon = { Icon(Icons.Rounded.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary) }
        ) {
            NotificationSoundPreset.entries.forEach { preset ->
                SoundChoiceRowV28(preset.title, preset.description, settings.notificationSound == preset,
                    onSelect = { onNotificationSelected(preset) }, onPreview = { onPreviewNotification(preset) })
            }
            OutlinedButton(onClick = onTestNotification, modifier = Modifier.fillMaxWidth()) {
                Text("تجربة إشعار منبثق")
            }
            OutlinedButton(onClick = onOpenNotificationSettings, modifier = Modifier.fillMaxWidth()) {
                Text("إعدادات التنبيه المنبثق")
            }
            Text("فعّل الظهور المنبثق والصوت من إعدادات الهاتف. وضع عدم الإزعاج أو كتم التنبيهات قد يمنع ظهوره أو صوته.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            notificationMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary) }
        }
        Text("اضغط على الخيار لعرض النغمات. الاختيار يُحفظ تلقائيًا، وزر «تجربة» يسمعك الصوت دون تغييره.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SoundGroupCardV28(
    title: String,
    selectedTitle: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(modifier = Modifier.fillMaxWidth()
                .semantics { stateDescription = if (expanded) "الأصوات معروضة" else "الأصوات مخفية" }
                .clickable(role = Role.Button, onClick = onToggle).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) { icon() }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("المختار: " + selectedTitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    content()
                }
            }
        }
    }
}

@Composable
private fun SoundChoiceRowV28(
    title: String, description: String, selected: Boolean,
    onSelect: () -> Unit, onPreview: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.VolumeUp, null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onPreview) {
                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                Text("تجربة")
            }
        }
    }
}

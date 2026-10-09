package com.radwan.raadpharmacy.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.radwan.raadpharmacy.cloud.CloudNotificationCenter
import com.radwan.raadpharmacy.cloud.CloudContinuousListening

internal data class BackgroundNotificationHealth(val notificationsAllowed: Boolean, val backgroundRestricted: Boolean, val batteryExempt: Boolean)

internal fun notificationHealth(context: Context) = BackgroundNotificationHealth(
    CloudNotificationCenter.canPost(context),
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
)

@Composable
internal fun BackgroundNotificationSettings() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var health by remember { mutableStateOf(notificationHealth(context)) }
    var continuous by remember { mutableStateOf(CloudContinuousListening.isEnabled(context)) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val running by CloudContinuousListening.running.collectAsState()
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                health = notificationHealth(context)
                continuous = CloudContinuousListening.isEnabled(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun openSettings(intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"))) }
        }
    }
    OutlinedButton(
        onClick = { showDetails = true },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Text("الإشعارات والعمل في الخلفية", style = MaterialTheme.typography.titleMedium)
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("الإشعارات والعمل في الخلفية") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("التشغيل المستمر في الخلفية", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = continuous, onCheckedChange = { enabled ->
                    continuous = enabled
                    CloudContinuousListening.setEnabled(context, enabled)
                    if (enabled) CloudContinuousListening.startFromVisibleApp(context)
                })
            }
            Text(if (running) "خدمة الخلفية تعمل الآن" else if (continuous) "خدمة الخلفية لم تبدأ بعد" else "التشغيل المستمر متوقف",
                style = MaterialTheme.typography.bodySmall)
            Text("تستمر متابعة التنبيهات في الخلفية مع إشعار نظام صامت ومختصر. بعد إعادة تشغيل الهاتف افتح التطبيق ليبدأ مجددًا. قد يزيد استهلاك البطارية.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("إذن الإشعارات: " + if (health.notificationsAllowed) "مفعّل" else "يحتاج السماح",
                style = MaterialTheme.typography.bodyMedium)
            Text("تشغيل الخلفية: " + if (health.backgroundRestricted) "مقيّد من الهاتف" else "مسموح",
                style = MaterialTheme.typography.bodyMedium)
            Text("البطارية: " + if (health.batteryExempt) "بدون تحسين البطارية" else "تحسين البطارية مفعّل",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }, modifier = Modifier.fillMaxWidth()) {
                Text("إعدادات الإشعارات والصوت")
            }
            OutlinedButton(onClick = { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth()) {
                Text("إعدادات تشغيل الخلفية")
            }
            Text("في إعدادات الهاتف اختر البطارية: غير مقيّد، وفعّل التشغيل التلقائي إذا توفر.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) { Text("إغلاق") }
            }
        )
    }
}

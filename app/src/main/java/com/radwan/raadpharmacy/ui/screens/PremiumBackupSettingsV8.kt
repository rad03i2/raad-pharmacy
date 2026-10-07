package com.radwan.raadpharmacy.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.BuildConfig
import com.radwan.raadpharmacy.PharmacyLedgerViewModel
import com.radwan.raadpharmacy.cloud.CloudSyncRuntime
import com.radwan.raadpharmacy.data.AutoBackupInterval
import com.radwan.raadpharmacy.data.BackupPreview
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.components.SectionTitle
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SettingsScreenV10(vm: PharmacyLedgerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()

    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var lastBackupAt by remember { mutableStateOf(vm.lastBackupAt()) }
    var autoInterval by remember { mutableStateOf(vm.autoBackupInterval()) }
    var pendingRestoreRaw by remember { mutableStateOf<String?>(null) }
    var pendingPreview by remember { mutableStateOf<BackupPreview?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }

    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                working = true
                val timestamp = System.currentTimeMillis()
                val result = runCatching {
                    val raw = withContext(Dispatchers.IO) { vm.createBackupJson() }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                            output.write(raw.toByteArray(Charsets.UTF_8))
                        } ?: error("تعذر فتح الملف للكتابة.")
                    }
                    vm.markManualBackupCreated(timestamp)
                }
                working = false
                if (result.isSuccess) {
                    lastBackupAt = timestamp
                    message = "تم إنشاء النسخة الاحتياطية وحفظها بنجاح."
                } else {
                    message = "تعذر حفظ النسخة الاحتياطية."
                }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                working = true
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                            it.readText()
                        } ?: error("تعذر قراءة الملف.")
                    }
                }
                working = false

                result.onSuccess { raw ->
                    val preview = withContext(Dispatchers.Default) {
                        vm.previewBackup(raw)
                    }
                    if (preview.valid) {
                        pendingRestoreRaw = raw
                        pendingPreview = preview
                    } else {
                        message = preview.message
                    }
                }.onFailure {
                    message = "تعذر قراءة ملف النسخة الاحتياطية."
                }
            }
        }
    }

    pendingPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = {
                pendingPreview = null
                pendingRestoreRaw = null
            },
            title = { Text("تأكيد استعادة النسخة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("تم فحص الملف بنجاح قبل الاستعادة.")
                    Text("الزبائن: " + preview.customerCount)
                    Text("الحركات: " + preview.entryCount)
                    if (preview.createdAt > 0L) {
                        Text(
                            "تاريخ النسخة: " +
                                formatDate(preview.createdAt) + " • " +
                                formatTime(preview.createdAt)
                        )
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            "قبل الاستبدال سيحفظ التطبيق تلقائيًا نسخة أمان داخلية من بياناتك الحالية.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val raw = pendingRestoreRaw ?: return@Button
                    pendingPreview = null
                    pendingRestoreRaw = null
                    scope.launch {
                        working = true
                        val result = withContext(Dispatchers.IO) {
                            vm.restoreBackup(raw)
                        }
                        working = false
                        message = result.message +
                            if (result.success) {
                                " (" + result.customerCount + " زبون، " +
                                    result.entryCount + " حركة)"
                            } else {
                                ""
                            }
                    }
                }) {
                    Text("استعادة")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingPreview = null
                    pendingRestoreRaw = null
                }) {
                    Text("إلغاء")
                }
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("إعادة البيانات التجريبية") },
            text = { Text("سيتم حذف البيانات الحالية وإعادة بيانات العرض الأولية.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        working = true
                        vm.resetDemoData()
                        working = false
                        showResetConfirm = false
                        message = "تمت إعادة البيانات التجريبية."
                    }
                }) { Text("إعادة") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("إلغاء") }
            }
        )
    }

    if (showAbout) {
        AboutDialogV15(onDismiss = { showAbout = false })
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { if (!working) showSignOutConfirm = false },
            title = { Text("تسجيل الخروج من الحساب؟") },
            text = {
                Text(
                    "ستبقى بيانات الدفتر محفوظة محليًا، وسيتوقف هذا الجهاز عن استقبال تحديثات الحساب حتى تسجيل الدخول مرة أخرى."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !working,
                    onClick = {
                        scope.launch {
                            working = true
                            CloudSyncRuntime.signOut(context)
                            working = false
                            showSignOutConfirm = false
                        }
                    }
                ) { Text("تسجيل الخروج") }
            },
            dismissButton = {
                TextButton(
                    enabled = !working,
                    onClick = { showSignOutConfirm = false }
                ) { Text("إلغاء") }
            }
        )
    }

    message?.let { currentMessage ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("دفتر صيدلية رعد") },
            text = { Text(currentMessage) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text("حسنًا") }
            }
        )
    }

    Scaffold(topBar = { ScreenTopBar("المزيد") }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.CloudDone,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(25.dp)
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text("حماية البيانات", style = MaterialTheme.typography.titleLarge)
                            Text(
                                customers.size.toString() + " زبون • " +
                                    entries.size.toString() + " حركة محفوظة",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                if (lastBackupAt > 0L) {
                                    "آخر نسخة: " + formatDate(lastBackupAt) +
                                        " • " + formatTime(lastBackupAt)
                                } else {
                                    "لم يتم إنشاء نسخة احتياطية بعد"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            item { SectionTitle("النسخ الاحتياطي") }

            item {
                V8SettingsRow(
                    Icons.Rounded.Backup,
                    "إنشاء نسخة احتياطية",
                    "اختر مكان الحفظ على الهاتف أو خدمة ملفات متصلة",
                    enabled = !working
                ) {
                    createBackupLauncher.launch(backupFileName())
                }
            }

            item {
                V8SettingsRow(
                    Icons.Rounded.Restore,
                    "استعادة نسخة",
                    "فحص الملف ومعاينته قبل استبدال البيانات",
                    enabled = !working
                ) {
                    restoreLauncher.launch(
                        arrayOf(
                            "application/json",
                            "text/plain",
                            "application/octet-stream"
                        )
                    )
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(15.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("النسخ التلقائي الداخلي", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "يحفظ التطبيق نسخة داخلية دورية عند استخدامه، ويحتفظ بأحدث 7 نسخ.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AutoBackupInterval.entries.forEach { interval ->
                                FilterChip(
                                    selected = autoInterval == interval,
                                    onClick = {
                                        scope.launch {
                                            autoInterval = interval
                                            vm.setAutoBackupInterval(interval)
                                            lastBackupAt = vm.lastBackupAt()
                                        }
                                    },
                                    label = { Text(intervalLabel(interval)) }
                                )
                            }
                        }
                    }
                }
            }

            if (working) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Text("جاري معالجة البيانات...")
                        }
                    }
                }
            }

            item { SectionTitle("التطبيق") }

            item {
                V8SettingsRow(
                    Icons.Rounded.Share,
                    "كشف الحساب",
                    "المشاركة متاحة من داخل ملف كل زبون"
                ) {
                    message = "افتح الزبون ثم كشف الحساب للمشاركة كصورة عبر WhatsApp أو الرسائل."
                }
            }
            item {
                V8SettingsRow(
                    Icons.Rounded.DarkMode,
                    "المظهر",
                    "يتبع إعداد الهاتف تلقائيًا"
                ) {
                    message = "الوضع الفاتح والداكن يتبعان إعداد الهاتف."
                }
            }
            item {
                V8SettingsRow(
                    Icons.Rounded.AccountBalanceWallet,
                    "العملة",
                    "الدينار العراقي • د.ع"
                ) { }
            }

            item { SectionTitle("الخط وحجم النص") }
            item { TypographySettingsCardV212(vm) }

            item { SectionTitle("الأصوات والتأكيدات") }
            item { FinancialSoundSettingsCardV28(vm) }

            item { SectionTitle("المتابعة الذكية") }
            item { SmartReminderSettingsCardV10(vm) }

            item { SectionTitle("الخصوصية والأمان") }
            item { SecuritySettingsCardV9(vm) }

            item { SectionTitle("الحساب السحابي") }
            item {
                V8SettingsRow(
                    Icons.AutoMirrored.Rounded.Logout,
                    "تسجيل الخروج",
                    "إيقاف مزامنة هذا الحساب على الجهاز والعودة لشاشة الدخول",
                    enabled = !working
                ) {
                    showSignOutConfirm = true
                }
            }

            item { SectionTitle("حول") }

            item {
                V8SettingsRow(
                    Icons.Rounded.Info,
                    "حول دفتر صيدلية رعد",
                    "الإصدار " + BuildConfig.VERSION_NAME + " • التطوير والتصميم: رضوان عبدالهادي"
                ) {
                    showAbout = true
                }
            }

            item {
                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text("إعادة البيانات التجريبية")
                }
            }

            item { Spacer(Modifier.height(6.dp)) }
        }
    }
}

@Composable
private fun V8SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Rounded.ChevronLeft,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun intervalLabel(interval: AutoBackupInterval): String =
    when (interval) {
        AutoBackupInterval.OFF -> "متوقف"
        AutoBackupInterval.DAILY -> "يومي"
        AutoBackupInterval.WEEKLY -> "أسبوعي"
    }

private fun backupFileName(): String {
    val stamp = LocalDateTime.now().format(
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.US)
    )
    return "RaadPharmacy-backup-" + stamp + ".json"
}

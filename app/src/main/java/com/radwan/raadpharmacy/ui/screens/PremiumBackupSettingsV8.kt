package com.radwan.raadpharmacy.ui.screens

import com.radwan.raadpharmacy.ui.components.rememberLedgerFlingBehavior

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
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.radwan.raadpharmacy.util.SignOutChallenge
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
    var showAbout by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var signOutAnswer by remember { mutableStateOf("") }
    var showTypography by remember { mutableStateOf(false) }
    var showAppUpdater by rememberSaveable { mutableStateOf(false) }
    var showLocalBackups by rememberSaveable { mutableStateOf(false) }

    if (showAbout) {
        AboutDialogV15(onDismiss = { showAbout = false })
    }

    if (showTypography) {
        AlertDialog(
            onDismissRequest = { showTypography = false },
            title = { Text("الخط وحجم النص") },
            text = { TypographySettingsCardV212(vm) },
            confirmButton = {
                TextButton(onClick = { showTypography = false }) {
                    Text("تم")
                }
            }
        )
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { if (!working) showSignOutConfirm = false },
            title = { Text("تسجيل الخروج من الحساب؟") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("للتأكيد، احسب المعادلة واكتب الناتج. ستبقى بيانات الدفتر محفوظة على الجهاز.")
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr
                    ) {
                        Text(SignOutChallenge.expression, style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(value = signOutAnswer,
                            onValueChange = { signOutAnswer = it.filter(Char::isDigit).take(8) },
                            label = { Text("الناتج") }, singleLine = true, enabled = !working,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !working && SignOutChallenge.accepts(signOutAnswer),
                    onClick = {
                        scope.launch {
                            working = true
                            try {
                                CloudSyncRuntime.signOut(context)
                                showSignOutConfirm = false
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                message = "تعذر تسجيل الخروج. حاول مرة أخرى."
                            } finally { working = false }
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

    if (showLocalBackups) {
        StorageBackupScreen(onBack = { showLocalBackups = false })
        return
    }

    if (showAppUpdater) {
        AppUpdateScreenV314(onBack = { showAppUpdater = false })
        return
    }

    Scaffold(topBar = { ScreenTopBar("الضبط") }) { padding ->
        LazyColumn(
            flingBehavior = rememberLedgerFlingBehavior(),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { CloudAccountPanelV330() }
            item { BackgroundNotificationSettings() }
            item { SectionTitle("البيانات والنسخ الاحتياطي") }

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
                                    Icons.Rounded.Save,
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
                                "تفاصيل الحماية في التخزين والنسخ الاحتياطي",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            item {
                V8SettingsRow(Icons.Rounded.Save, "التخزين والنسخ الاحتياطي",
                    "النسخ على الهاتف والبطاقة والسحابة من شاشة واحدة") { showLocalBackups = true }
            }

            item { SectionTitle("التطبيق") }
            item {
                V8SettingsRow(
                    Icons.Rounded.FormatSize,
                    "الخط وحجم النص",
                    "اختيار الخط وضبط حجم النص من مكان واحد"
                ) {
                    showTypography = true
                }
            }

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
                    signOutAnswer = ""
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
                V8SettingsRow(
                    Icons.Rounded.SystemUpdate,
                    "تحديث التطبيق",
                    "معلومات النسخة، البحث عن تحديثات جديدة، التنزيل والتثبيت الآمن"
                ) { showAppUpdater = true }
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

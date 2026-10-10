package com.radwan.raadpharmacy.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.backup.*
import com.radwan.raadpharmacy.ui.components.ScreenTopBar

internal enum class AutoBackupPage(val title: String) {
    HOME("النسخ الاحتياطي"), PHONE("نسخة الهاتف"), SD("بطاقة الذاكرة"),
    DRIVE("Google Drive"), RESTORE("استعادة نسخة")
}
internal enum class AutoBackupAction { UPDATE, SD_FOLDER, SD_TOGGLE, GOOGLE_CONNECT, GOOGLE_DISCONNECT, GOOGLE_RETRY, EXPORT, IMPORT, CLOUD_HISTORY, LEGACY, RECONCILE }

@Composable
internal fun AutomaticBackupContent(page: AutoBackupPage, state: AutomaticBackupState, sequence: Long,
    working: Boolean, held: Boolean, history: List<PortableBackupItem>, onBack: () -> Unit,
    onPage: (AutoBackupPage) -> Unit, onAction: (AutoBackupAction) -> Unit, onPreview: (PortableBackupItem) -> Unit
) {
    val list = remember(page) { LazyListState() }
    val enabledPlaces = listOf(state.phone, state.sd, state.drive).filter { it.enabled }
    val hasError = enabledPlaces.any { it.error != null }
    val pending = enabledPlaces.any { it.sequence != sequence || it.updatedAt == 0L }
    Scaffold(topBar = { ScreenTopBar(page.title, onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("automatic-backup-list"), state = list,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (working || state.busy || state.uploading) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (state.uploading) "جارٍ تحديث النسخة السحابية…" else "جارٍ تحديث النسخة…", style = MaterialTheme.typography.bodySmall)
            }
            if (held) item { BackupPanel("البيانات المستعادة قيد المراجعة", warning = true) {
                Text("المزامنة والتعديل متوقفان حتى مراجعة النسخة المستعادة.")
                TextButton(enabled = !working, onClick = { onAction(AutoBackupAction.RECONCILE) }) { Text("العودة إلى بيانات الصيدلية الحالية") }
            } }
            when (page) {
                AutoBackupPage.HOME -> {
                    item { BackupPanel("حالة النسخ الاحتياطية", if (hasError) Icons.Rounded.WarningAmber
                        else if (pending) Icons.Rounded.Schedule else Icons.Rounded.CheckCircle, warning = hasError) {
                        Text("تتحدّث النسخة تلقائيًا بعد إضافة زبون أو تغيير دين أو تحصيل.")
                        AutoBackupSummary("الهاتف", state.phone, sequence)
                        AutoBackupSummary("بطاقة الذاكرة", state.sd, sequence)
                        AutoBackupSummary("Google Drive", state.drive, sequence)
                    } }
                    item { Text("أماكن النسخ", style = MaterialTheme.typography.titleMedium) }
                    item { BackupNavigationRow(Icons.Rounded.PhoneAndroid, "الهاتف",
                        automaticBackupStatus(state.phone, sequence), !working, state.phone.error != null,
                        "auto-phone", { onPage(AutoBackupPage.PHONE) }) }
                    item { BackupNavigationRow(Icons.Rounded.SdCard, "بطاقة الذاكرة",
                        automaticBackupStatus(state.sd, sequence), !working, state.sd.enabled && state.sd.error != null,
                        "auto-sd", { onPage(AutoBackupPage.SD) }) }
                    item { BackupNavigationRow(Icons.Rounded.Cloud, "النسخ السحابي",
                        if (state.account == null) "ربط حساب Google" else automaticBackupStatus(state.drive, sequence),
                        !working, state.drive.enabled && state.drive.error != null, "auto-drive", { onPage(AutoBackupPage.DRIVE) }) }
                    item {
                        OutlinedButton(enabled = !working, onClick = { onPage(AutoBackupPage.RESTORE) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp).testTag("auto-restore")) {
                            Icon(Icons.Rounded.Restore, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("استعادة نسخة")
                        }
                    }
                }
                AutoBackupPage.PHONE -> {
                    item { BackupPanel("نسخة تلقائية على الهاتف", Icons.Rounded.PhoneAndroid) {
                        AutoBackupDetails(state.phone, sequence)
                        Text("المجلد: Download / ${PortableBackup.FOLDER}", style = MaterialTheme.typography.bodySmall)
                        Text("ينشئ التطبيق المجلد عند أول تشغيل. تبقى النسخ فيه بعد إزالة التطبيق، ويمكن اختيار الملف للاستعادة على هاتف آخر.")
                        Text("تُحفظ نسخة كاملة جديدة بعد كل تغيّر، مع النسختين السابقتين. لا تحتاج النسخ الجديدة إلى مفتاح استرداد.", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !working, onClick = { onAction(AutoBackupAction.UPDATE) }, modifier = Modifier.fillMaxWidth()) { Text("تحديث النسخة الآن") }
                    } }
                    item { BackupPanel("حفظ ملف في مكان آخر", Icons.Rounded.Folder) {
                        Text("ملف واحد يشمل الزبائن والديون والتحصيلات والصور.")
                        OutlinedButton(enabled = !working, onClick = { onAction(AutoBackupAction.EXPORT) }, modifier = Modifier.fillMaxWidth()) { Text("حفظ نسخة كملف") }
                        Text("ملف النسخة يحتوي بيانات الزبائن. احتفظ به في مكان خاص.", style = MaterialTheme.typography.bodySmall)
                    } }
                }
                AutoBackupPage.SD -> item { BackupPanel("نسخة تلقائية على البطاقة", Icons.Rounded.SdCard) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("النسخ التلقائي", Modifier.weight(1f))
                        Switch(state.sd.enabled, { onAction(AutoBackupAction.SD_TOGGLE) }, enabled = !working,
                            modifier = Modifier.testTag("auto-sd-switch"))
                    }
                    AutoBackupDetails(state.sd, sequence)
                    Text("اختر مجلد البطاقة مرة واحدة لمنح الإذن. بعدها تتحدّث النسخة بنفس بيانات نسخة الهاتف تلقائيًا.")
                    Text("إذا أخرجت البطاقة، تبقى نسخة الهاتف تعمل وتُحدّث البطاقة عند إعادتها.", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !working, onClick = { onAction(AutoBackupAction.SD_FOLDER) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.sd.location.isBlank()) "اختيار مجلد البطاقة" else "تغيير المجلد أو تجديد الإذن")
                    }
                } }
                AutoBackupPage.DRIVE -> item { BackupPanel("نسخة على Google Drive", Icons.Rounded.Cloud) {
                    AutoBackupDetails(state.drive, sequence)
                    state.account?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text("اربط حساب Google لحفظ نفس ملف النسخة على Drive. بعد كل تغيّر تُرفع النسخة المحدّثة تلقائيًا عند توفر الإنترنت.")
                    Text("لكل هاتف مجلد مستقل داخل ${PortableBackup.FOLDER}؛ لا يستبدل نسخة الهواتف الأخرى.", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !working && !state.uploading, onClick = { onAction(AutoBackupAction.GOOGLE_CONNECT) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.account == null) "ربط حساب Google" else "تجديد إذن حساب Google")
                    }
                    if (state.account != null) {
                        OutlinedButton(enabled = !working && !state.uploading, onClick = { onAction(AutoBackupAction.GOOGLE_RETRY) }, modifier = Modifier.fillMaxWidth()) { Text("تحديث النسخة السحابية") }
                        TextButton(enabled = !working, onClick = { onAction(AutoBackupAction.GOOGLE_DISCONNECT) }) { Text("إيقاف الربط") }
                    }
                } }
                AutoBackupPage.RESTORE -> {
                    item { Text("اختر النسخة، راجع عدد الزبائن والعمليات، ثم أكّد الاستعادة. اختيار الملف وحده لا يغيّر البيانات.") }
                    item { Button(enabled = !working, onClick = { onAction(AutoBackupAction.IMPORT) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("اختيار ملف من الهاتف أو البطاقة")
                    } }
                    if (state.account != null) item { OutlinedButton(enabled = !working, onClick = { onAction(AutoBackupAction.CLOUD_HISTORY) }, modifier = Modifier.fillMaxWidth()) { Text("عرض نسخ Google Drive") } }
                    if (history.isNotEmpty()) item { Text("النسخ المتاحة • الأحدث أولًا", style = MaterialTheme.typography.titleMedium) }
                    items(history.size, key = { "${history[it].place}/${history[it].locator}" }) { index ->
                        val copy = history[index]
                        BackupPanel(when (copy.place) { "sd" -> "بطاقة الذاكرة"; "drive" -> "Google Drive"; else -> "الهاتف" }) {
                            Text(backupTime(copy.createdAt)); Text(backupSize(copy.bytes), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(enabled = !working, onClick = { onPreview(copy) }, modifier = Modifier.fillMaxWidth()) { Text("معاينة هذه النسخة") }
                        }
                    }
                    item { TextButton(enabled = !working, onClick = { onAction(AutoBackupAction.LEGACY) }) { Text("فتح نسخ قديمة مشفّرة أو مجلد نسخ قديم") } }
                }
            }
        }
    }
}

@Composable private fun AutoBackupSummary(label: String, place: AutomaticBackupPlace, latest: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("$label: ${automaticBackupStatus(place, latest)}", style = MaterialTheme.typography.bodyMedium,
            color = if (place.enabled && place.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (place.enabled && place.updatedAt > 0) Text("آخر تحديث: ${backupTime(place.updatedAt)}", style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun AutoBackupDetails(place: AutomaticBackupPlace, latest: Long) {
    Text(automaticBackupStatus(place, latest), color = if (place.enabled && place.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    if (place.updatedAt > 0) {
        Text("آخر تحديث ناجح: ${backupTime(place.updatedAt)}")
        Text("الحجم: ${backupSize(place.bytes)}", style = MaterialTheme.typography.bodySmall)
    }
}

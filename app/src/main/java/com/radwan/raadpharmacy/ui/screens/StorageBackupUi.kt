package com.radwan.raadpharmacy.ui.screens

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.backup.BackupDestinationEntity
import com.radwan.raadpharmacy.backup.BackupHistoryItem
import com.radwan.raadpharmacy.backup.LocalBackupEngine
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatTime
import java.util.Locale

internal enum class BackupPage(val title: String) {
    HOME("التخزين والنسخ الاحتياطي"), PHONE("النسخ على الهاتف"), SD("بطاقة الذاكرة"),
    RESTORE("استعادة البيانات"), HISTORY("النسخ المحفوظة"), CLOUD("النسخ السحابي")
}
internal enum class BackupAction { BACKUP, PHONE_FOLDER, SD_FOLDER, SD_TOGGLE, EXPORT, KEY, IMPORT_FOLDER, IMPORT_FILE, RECONCILE }
internal data class BackupDestinationStatus(val label: String, val warning: Boolean, val pending: Long)

internal fun backupDestinationStatus(state: BackupDestinationEntity?, latest: Long): BackupDestinationStatus {
    val pending = if (state?.enabled == true) (latest - state.cursor).coerceAtLeast(0) else 0
    return when {
        state == null -> BackupDestinationStatus("غير مفعّل", false, 0)
        !state.enabled -> BackupDestinationStatus("متوقف", false, 0)
        state.error != null -> BackupDestinationStatus("يحتاج مراجعة", true, pending)
        state.lastFullAt <= 0 -> BackupDestinationStatus("النسخة الأولى لم تكتمل", true, pending)
        pending > 0 -> BackupDestinationStatus("تغييرات بانتظار النسخ", true, pending)
        else -> BackupDestinationStatus("محدّث", false, 0)
    }
}

/** A successful write message requires a verified full backup and a drained queue. */
internal fun backupWriteResult(states: List<BackupDestinationEntity>, latest: Long): String {
    val active = states.filter { it.enabled }
    if (active.isEmpty()) return "تعذر النسخ. لم يتم إعداد مكان للحفظ بعد."
    val incomplete = active.filter { backupDestinationStatus(it, latest).warning }
    return if (incomplete.isEmpty()) "تم إنشاء النسخة والتحقق من سلامتها في أماكن الحفظ المحلية المفعّلة."
    else "تعذر إكمال النسخ إلى: ${incomplete.joinToString("، ") { destinationLabel(it.id) }}. افتح المكان للتحقق من الإذن والمساحة، ثم حاول مجددًا."
}
internal fun backupTime(time: Long) = if (time <= 0) "لا توجد نسخة بعد" else "${formatDate(time)} • ${formatTime(time)}"
internal fun backupSize(bytes: Long) = String.format(Locale.US, "%.2f MB", bytes / 1048576.0)
internal fun backupErrorMessage(error: Exception): String {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(6).toList()
    return when {
        causes.any { it is javax.crypto.AEADBadTagException } -> "تعذر فتح النسخة: المفتاح غير مطابق أو الملف تالف. إذا كانت من هاتف آخر أو تثبيت سابق، اختر ذلك وأدخل مفتاحها."
        causes.any { it is SecurityException } -> "فُقد إذن الوصول إلى الملفات. اختر مجلد النسخ مجددًا لمنح الإذن، ثم حاول مرة أخرى."
        causes.any { it is org.json.JSONException } -> "الملف ليس نسخة احتياطية صالحة أو أنه ناقص. اختر ملف النسخة الكامل أو مجلد النسخ."
        causes.any { it is java.io.IOException } -> "تعذر الوصول إلى الملف أو حفظ النسخة. تحقق من توفر البطاقة والمساحة، ثم اختر المجلد مجددًا."
        else -> error.message?.takeIf { it.contains(Regex("[ء-ي]")) }?.take(320)
            ?: "تعذرت العملية. تحقق من ملف النسخة ومفتاح الاسترداد والمساحة المتاحة، ثم حاول مجددًا."
    }
}
internal fun destinationLabel(id: String) = when (id) {
    LocalBackupEngine.PRIVATE -> "داخل التطبيق"
    LocalBackupEngine.SD -> "بطاقة الذاكرة"
    else -> "مجلد الهاتف"
}

@Composable
internal fun StorageBackupContent(
    page: BackupPage, destinations: List<BackupDestinationEntity>, sequence: Long, busy: Boolean,
    confirmed: Boolean, held: Boolean, history: List<BackupHistoryItem>, onBack: () -> Unit,
    onPage: (BackupPage) -> Unit, onAction: (BackupAction) -> Unit, onPreview: (BackupHistoryItem) -> Unit
) {
    val private = destinations.firstOrNull { it.id == LocalBackupEngine.PRIVATE }
    val phone = destinations.firstOrNull { it.id == LocalBackupEngine.SHARED }
    val sd = destinations.firstOrNull { it.id == LocalBackupEngine.SD }
    val listState = remember(page) { androidx.compose.foundation.lazy.LazyListState() }
    Scaffold(topBar = { ScreenTopBar(page.title, onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("backup-list"), state = listState, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("جارٍ العمل…", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (held) item {
                BackupPanel("البيانات المستعادة قيد المراجعة", warning = true) {
                    Text("المزامنة والتعديل متوقفان لحماية بيانات الصيدلية الحالية.")
                    TextButton(enabled = !busy, onClick = { onAction(BackupAction.RECONCILE) }) { Text("العودة إلى بيانات الصيدلية الحالية") }
                }
            }
            when (page) {
                BackupPage.HOME -> {
                    item {
                        BackupPanel("احفظ نسخة من بياناتك", icon = Icons.Rounded.Save) {
                            val status = backupDestinationStatus(private, sequence)
                            Text(if (private == null) "جارٍ إعداد الحماية داخل التطبيق" else "حماية داخل التطبيق: ${status.label}",
                                color = if (status.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("آخر نسخة داخل التطبيق: ${backupTime(private?.lastFullAt ?: 0)}", style = MaterialTheme.typography.bodySmall)
                            Button(enabled = !busy, onClick = { onAction(BackupAction.BACKUP) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Icon(Icons.Rounded.Save, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("إنشاء نسخة محلية الآن")
                            }
                            OutlinedButton(enabled = !busy, onClick = { onPage(BackupPage.RESTORE) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Icon(Icons.Rounded.Restore, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("استعادة نسخة")
                            }
                        }
                    }
                    item { Text("أماكن حفظ النسخ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
                    item {
                        BackupNavigationRow(Icons.Rounded.PhoneAndroid, "الهاتف",
                            if (phone == null) "نسخة داخل التطبيق فقط" else "مجلد الهاتف: ${backupDestinationStatus(phone, sequence).label}",
                            enabled = !busy, warning = phone?.let { backupDestinationStatus(it, sequence).warning } == true,
                            tag = "backup-phone", onClick = { onPage(BackupPage.PHONE) })
                    }
                    item {
                        BackupNavigationRow(Icons.Rounded.SdCard, "بطاقة الذاكرة",
                            backupDestinationStatus(sd, sequence).label, enabled = !busy,
                            warning = backupDestinationStatus(sd, sequence).warning, tag = "backup-sd", onClick = { onPage(BackupPage.SD) })
                    }
                    item {
                        BackupNavigationRow(Icons.Rounded.Cloud, "النسخ السحابي", "Google Drive • عرض حالة الخدمة",
                            enabled = !busy, tag = "backup-cloud", onClick = { onPage(BackupPage.CLOUD) })
                    }
                    item {
                        BackupNavigationRow(Icons.Rounded.Key, "مفتاح الاسترداد", if (confirmed) "محفوظ • عرض أو نسخ المفتاح" else "احفظه لفتح النسخ على هاتف آخر",
                            enabled = !busy, tag = "backup-key", onClick = { onAction(BackupAction.KEY) })
                    }
                    if (phone == null && sd?.enabled != true) item {
                        Text("نسخة التطبيق تُحذف عند إزالة التطبيق. اختر مجلدًا على الهاتف أو بطاقة الذاكرة لحفظ نسخة خارجه.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                BackupPage.PHONE -> {
                    item {
                        BackupPanel("داخل التطبيق", icon = Icons.Rounded.Shield) {
                            BackupDestinationDetails(private, sequence)
                            Text("يحفظ التطبيق التغييرات تلقائيًا. هذه النسخ تُحذف عند إزالة التطبيق.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        BackupPanel("مجلد على الهاتف", icon = Icons.Rounded.Folder) {
                            BackupDestinationDetails(phone, sequence)
                            Text("نسخة مشفرة تبقى خارج التطبيق. اختر مجلدًا مثل Documents؛ ينشئ التطبيق مجلد النسخ داخله.", style = MaterialTheme.typography.bodySmall)
                            Button(enabled = !busy, onClick = { onAction(BackupAction.PHONE_FOLDER) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (phone == null) "اختيار مجلد النسخ" else "تغيير المجلد أو تجديد الإذن")
                            }
                        }
                    }
                    item {
                        BackupPanel("حفظ نسخة كملف") {
                            Text("احفظ ملفًا واحدًا مشفرًا في المكان الذي تختاره. الملف يمثل البيانات وقت إنشائه.", style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(enabled = !busy, onClick = { onAction(BackupAction.EXPORT) }, modifier = Modifier.fillMaxWidth()) { Text("حفظ ملف نسخة احتياطية") }
                        }
                    }
                }
                BackupPage.SD -> {
                    item {
                        BackupPanel("النسخ إلى بطاقة الذاكرة", icon = Icons.Rounded.SdCard) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (sd?.enabled == true) "النسخ التلقائي مفعّل" else "النسخ التلقائي غير مفعّل", Modifier.weight(1f))
                                Switch(sd?.enabled == true, enabled = !busy, modifier = Modifier.testTag("backup-sd-switch"),
                                    onCheckedChange = { onAction(BackupAction.SD_TOGGLE) })
                            }
                            BackupDestinationDetails(sd, sequence)
                            Text("اختر مجلدًا داخل بطاقة الذاكرة. عند إخراجها تبقى التغييرات محفوظة وتُنسخ عند توفر البطاقة مجددًا.", style = MaterialTheme.typography.bodySmall)
                            Button(enabled = !busy, onClick = { onAction(BackupAction.SD_FOLDER) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (sd == null) "اختيار مجلد البطاقة" else "تغيير المجلد أو تجديد الإذن")
                            }
                            if (sd?.enabled == true) OutlinedButton(enabled = !busy, onClick = { onAction(BackupAction.BACKUP) }, modifier = Modifier.fillMaxWidth()) { Text("إنشاء نسخة الآن") }
                        }
                    }
                }
                BackupPage.RESTORE -> {
                    item { Text("اختر مصدر النسخة. ستظهر معاينة قبل الاستعادة، ولن تتغير بياناتك بمجرد اختيار الملف.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { BackupNavigationRow(Icons.Rounded.History, "النسخ المحفوظة", "عرض النسخ الموجودة على هذا الهاتف والبطاقة", !busy, tag = "backup-history", onClick = { onPage(BackupPage.HISTORY) }) }
                    item { BackupNavigationRow(Icons.Rounded.Folder, "من مجلد نسخ", "نسخة كاملة مع التغييرات اللاحقة", !busy, tag = "backup-import-folder", onClick = { onAction(BackupAction.IMPORT_FOLDER) }) }
                    item { BackupNavigationRow(Icons.Rounded.Description, "من ملف نسخة", "ملف مشفر أو نسخة JSON قديمة", !busy, tag = "backup-import-file", onClick = { onAction(BackupAction.IMPORT_FILE) }) }
                    item { Text("يلزم مفتاح الاسترداد للنسخ المشفرة التي أنشأها هاتف آخر.", style = MaterialTheme.typography.bodySmall) }
                }
                BackupPage.HISTORY -> {
                    if (history.isEmpty()) item { BackupPanel("لا توجد نسخ متاحة") {
                        Text("أنشئ نسخة أولًا. إذا كانت النسخ على بطاقة الذاكرة، تأكد من توصيلها وتفعيلها.")
                    } }
                    items(history.size, key = { "${history[it].destination}/${history[it].name}" }) { index ->
                        val item = history[index]
                        BackupPanel(destinationLabel(item.destination), icon = Icons.Rounded.History) {
                            Text(backupTime(item.createdAt)); Text(backupSize(item.bytes), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(enabled = !busy, onClick = { onPreview(item) }, modifier = Modifier.fillMaxWidth()) { Text("فحص النسخة ومعاينتها") }
                        }
                    }
                }
                BackupPage.CLOUD -> Unit
            }
        }
    }
}

@Composable
internal fun BackupPanel(title: String, icon: ImageVector? = null, warning: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(
        containerColor = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                icon?.let { Icon(it, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
internal fun BackupNavigationRow(icon: ImageVector, title: String, description: String, enabled: Boolean,
    warning: Boolean = false, tag: String = "", onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronLeft, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BackupDestinationDetails(state: BackupDestinationEntity?, latest: Long) {
    val status = backupDestinationStatus(state, latest)
    Text(status.label, color = if (status.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    if (state != null) {
        Text("آخر نسخة كاملة: ${backupTime(state.lastFullAt)}")
        if (state.lastChangeAt > state.lastFullAt) Text("آخر تحديث للنسخة: ${backupTime(state.lastChangeAt)}", style = MaterialTheme.typography.bodySmall)
        if (status.pending > 0) Text("تغييرات تنتظر النسخ: ${status.pending}", style = MaterialTheme.typography.bodySmall)
        if (state.bytes > 0) Text("الحجم: ${backupSize(state.bytes)}", style = MaterialTheme.typography.bodySmall)
        state.treeUri?.let { value ->
            val folder = runCatching { DocumentsContract.getDocumentId(Uri.parse(value)).substringAfter(':') }.getOrDefault("مجلد النسخ")
            Text("المجلد: $folder", style = MaterialTheme.typography.bodySmall)
        }
        if (state.enabled) state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

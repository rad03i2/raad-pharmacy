package com.radwan.raadpharmacy.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.backup.*
import com.radwan.raadpharmacy.cloud.CloudSyncEngine
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.util.formatDate
import com.radwan.raadpharmacy.util.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StorageBackupScreen(onBack: () -> Unit) {
    var showCloud by remember { mutableStateOf(false) }
    if (showCloud) { CentralCloudBackupScreen { showCloud = false }; return }
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val engine = remember { LocalBackupEngine.get(context) }
    val scope = rememberCoroutineScope()
    val destinations by engine.destinations.collectAsState(initial = emptyList())
    val sequence by engine.sequence.collectAsState(initial = 0L)
    val engineBusy by engine.busy.collectAsState()
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var held by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<BackupHistoryItem>()) }
    var pickingSd by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var savedCheck by remember { mutableStateOf(false) }
    var importMode by remember { mutableStateOf<String?>(null) }
    var importCode by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<RestoredArchive?>(null) }
    var reconcileConfirm by remember { mutableStateOf(false) }
    fun act(block: suspend () -> String) {
        if (working) return
        scope.launch {
            working = true
            try {
                val result = withContext(Dispatchers.IO) { block() }
                message = if (showKey || preview != null) null else result
            }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) { message = e.message ?: "تعذرت العملية. تحقق من الملف والمفتاح والإذن والمساحة." }
            finally { working = false; held = withContext(Dispatchers.IO) { engine.held() } }
        }
    }
    LaunchedEffect(destinations, sequence, engineBusy, working) {
        withContext(Dispatchers.IO) {
            held = engine.held(); confirmed = engine.recoveryConfirmed(); history = engine.history()
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) act { engine.configureFolder(uri, pickingSd); "تم إعداد الوجهة. راجع حالتها للتأكد من اكتمال النسخ." }
    }
    val restoreFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) act { preview = engine.previewFolder(uri, importCode); "تم التحقق من سلسلة النسخ." }
    }
    val restoreFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) act { preview = engine.previewFile(uri, importCode); "تم فحص النسخة. اختر المجلد لاستعادة التغييرات اللاحقة." }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) act { engine.exportSnapshot(uri); "تم تصدير نسخة كاملة مشفرة والتحقق منها." }
    }
    if (showKey) AlertDialog(onDismissRequest = { showKey = false; code = "" }, title = { Text("مفتاح الاسترداد") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("احفظ هذا المفتاح في مكان آمن منفصل عن الهاتف. يلزم لفتح النسخ على هاتف آخر. من يملك المفتاح يستطيع قراءة النسخة.")
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                SelectionContainer { Text(code, style = MaterialTheme.typography.bodyMedium) }
            }
            Row { Checkbox(savedCheck, onCheckedChange = { savedCheck = it }); Text("حفظت المفتاح خارج الهاتف", Modifier.padding(top = 12.dp)) }
        }
    }, confirmButton = { TextButton(enabled = savedCheck, onClick = {
        act { engine.confirmRecovery(); confirmed = true; "تم تأكيد حفظ المفتاح." }; showKey = false; code = ""
    }) { Text("تأكيد") } }, dismissButton = { TextButton(onClick = { showKey = false; code = "" }) { Text("إغلاق") } })
    importMode?.let { mode -> AlertDialog(onDismissRequest = { importMode = null }, title = { Text("استيراد نسخة احتياطية") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("أدخل مفتاح استرداد الهاتف الذي أنشأ النسخة. النسخ القديمة بصيغة JSON لا تحتاج مفتاحًا.")
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(importCode, onValueChange = { importCode = it }, label = { Text("مفتاح الاسترداد") }, modifier = Modifier.fillMaxWidth())
            }
            if (mode == "folder") Text("اختر مجلد Raad Pharmacy Backups الذي يحتوي Snapshots وChanges وMetadata.")
        }
    }, confirmButton = { TextButton(onClick = {
        importMode = null
        if (mode == "folder") restoreFolder.launch(null) else restoreFile.launch(arrayOf("application/octet-stream", "application/json", "text/plain"))
    }) { Text("اختيار") } }, dismissButton = { TextButton(onClick = { importMode = null; importCode = "" }) { Text("إلغاء") } }) }
    preview?.let { archive -> AlertDialog(onDismissRequest = { if (!working) preview = null }, title = { Text("معاينة الاستعادة") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("الزبائن: ${archive.ledger.customers.size} • الحركات: ${archive.ledger.entries.size}")
            Text("صور الزبائن: ${archive.photos.size} • تاريخ البيانات: ${backupTime(archive.ledger.createdAt)}")
            Text("تُحفظ نسخة أمان قبل الاستبدال. تُعلّق المزامنة والتعديل بعد الاستعادة لحماية الحركات الأحدث في الحساب المركزي. العمليات المعلقة محفوظة للمراجعة ولا تُرفع تلقائيًا.")
        }
    }, confirmButton = { TextButton(enabled = !working, onClick = {
        preview = null; importCode = ""; act { engine.restore(archive).message }
    }) { Text("استعادة محلية") } }, dismissButton = { TextButton(onClick = { preview = null; importCode = "" }) { Text("إلغاء") } }) }
    if (reconcileConfirm) AlertDialog(onDismissRequest = { reconcileConfirm = false }, title = { Text("اعتماد الحالة المركزية الحالية؟") },
        text = { Text("سيحفظ التطبيق نسخة من الحالة المحلية ثم يحمل بيانات الحساب المركزي الحالية ويستأنف المزامنة. النسخة المستعادة والعمليات المعلقة تبقى في النسخ المشفرة للمراجعة، ولن تُنشر تلقائيًا.") },
        confirmButton = { TextButton(enabled = !working, onClick = {
            reconcileConfirm = false; act { CloudSyncEngine(context).reconcileRestoredFromServer(); "تم تحميل الحالة المركزية واستئناف المزامنة." }
        }) { Text("تحميل الحالة المركزية") } }, dismissButton = { TextButton(onClick = { reconcileConfirm = false }) { Text("إلغاء") } })
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("النسخ الاحتياطي") },
        text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("حسنًا") } }) }
    Scaffold(topBar = { ScreenTopBar("التخزين والنسخ الاحتياطي", onBack) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).testTag("backup-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { BackupCard("النسخ السحابي") {
                Text("حالة النسخ الاحتياطي المركزي لجميع مستخدمي الصيدلية.")
                OutlinedButton(onClick = { showCloud = true }) { Text("عرض النسخ الاحتياطي السحابي المركزي") }
            } }
            item { BackupCard("حماية مستمرة للبيانات") {
                Text("كل تغيير مالي محفوظ مع سجل حماية دائم. تُرحّل التغييرات في الخلفية، وتُنشأ نسخ كاملة كل 6 ساعات عند وجود تغييرات، مع نسخة يومية مرجعية.")
                Text("الحماية الخاصة تُحذف عند إزالة التطبيق. النسخ داخل المجلد المشترك وعلى SD تبقى ما دام المستخدم لم يحذفها.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !working, onClick = { act { code = engine.recoveryCode(); showKey = true; savedCheck = confirmed; "احفظ مفتاح الاسترداد." } }) { Text(if (confirmed) "عرض مفتاح الاسترداد" else "حفظ مفتاح الاسترداد") }
            } }
            if (held) item { BackupCard("الاستعادة المحلية معلقة عن السحابة", true) {
                Text("البيانات للعرض حاليًا. المزامنة والتعديل موقوفان؛ لن يعيد الهاتف نشر الديون القديمة. احتفظ بالنسخة لمراجعة العمليات غير المتزامنة.")
                Button(enabled = !working, onClick = { reconcileConfirm = true }) { Text("مصالحة مع الحساب المركزي") }
            } }
            item { BackupCard("النسخ الداخلية") {
                DestinationDetails(destinations.firstOrNull { it.id == LocalBackupEngine.PRIVATE }, sequence, "مساحة التطبيق الخاصة")
                HorizontalDivider()
                DestinationDetails(destinations.firstOrNull { it.id == LocalBackupEngine.SHARED }, sequence, "المجلد الظاهر على الهاتف")
                Button(enabled = !working, onClick = { act { engine.process(force = true); "انتهت محاولة النسخ. راجع حالة كل وجهة." } }) { Text("إنشاء نسخة الآن") }
                OutlinedButton(enabled = !working && confirmed, onClick = { pickingSd = false; folderPicker.launch(null) }) { Text("إعداد أو تغيير مجلد النسخ على الهاتف") }
                if (!confirmed) Text("احفظ مفتاح الاسترداد لتفعيل النسخ خارج التطبيق.")
            } }
            item { BackupCard("بطاقة الذاكرة SD") {
                val state = destinations.firstOrNull { it.id == LocalBackupEngine.SD }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (state?.enabled == true) "النسخ إلى البطاقة مفعل" else "النسخ إلى البطاقة معطل", Modifier.weight(1f))
                    Switch(checked = state?.enabled == true, enabled = !working && confirmed, onCheckedChange = { enable ->
                        if (state == null && enable) { pickingSd = true; folderPicker.launch(null) }
                        else act { engine.setSdEnabled(enable); if (enable) "تم تفعيل النسخ إلى البطاقة." else "تم تعطيل النسخ إلى البطاقة." }
                    })
                }
                DestinationDetails(state, sequence, "نسخ مستقل دون إنترنت")
                Text("عند إزالة البطاقة تبقى التغييرات في الطابور المحلي، وتستمر النسخ الداخلية.")
                OutlinedButton(enabled = !working && confirmed, onClick = { pickingSd = true; folderPicker.launch(null) }) { Text("تحديد أو تغيير مجلد البطاقة") }
                Button(enabled = !working && state?.enabled == true, onClick = { act { engine.process(); "انتهت محاولة ترحيل التغييرات. راجع حالة البطاقة." } }) { Text("النسخ الآن") }
            } }
            item { BackupCard("الاستعادة وسجل النسخ") {
                Text("استيراد المجلد يعيد النسخة الكاملة مع التغييرات اللاحقة. استيراد ملف كامل يعيد حالة ذلك الملف فقط.")
                OutlinedButton(enabled = !working, onClick = { importCode = ""; importMode = "folder" }) { Text("استيراد مجلد نسخ من الهاتف أو SD") }
                OutlinedButton(enabled = !working, onClick = { importCode = ""; importMode = "file" }) { Text("استيراد ملف مشفر أو نسخة JSON قديمة") }
                OutlinedButton(enabled = !working && confirmed, onClick = { exportFile.launch("RaadPharmacy-${System.currentTimeMillis()}.rpb") }) { Text("تصدير نسخة كاملة مشفرة") }
                if (history.isEmpty()) Text("لا توجد نسخ كاملة متاحة بعد.")
            } }
            items(history.size, key = { "${history[it].destination}/${history[it].name}" }) { index ->
                val item = history[index]
                BackupCard("نسخة كاملة • ${destinationLabel(item.destination)}") {
                    Text(backupTime(item.createdAt)); Text(backupSize(item.bytes))
                    OutlinedButton(enabled = !working, onClick = { act { preview = engine.previewHistory(item); "تم التحقق من النسخة والتغييرات اللاحقة." } }) { Text("التحقق والمعاينة") }
                }
            }
            if (working || engineBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item { Text("قد يتأخر النسخ بسبب قيود أندرويد أو فقدان الإذن أو امتلاء الذاكرة أو عدم توفر SD. كل وجهة تعرض آخر كتابة تم التحقق منها.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
@Composable private fun BackupCard(title: String, warning: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(
        containerColor = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); content() }
    }
}
@Composable private fun DestinationDetails(state: BackupDestinationEntity?, latest: Long, title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    val pending = (latest - (state?.cursor ?: 0)).coerceAtLeast(0)
    Text(when { state == null -> "غير مهيأ"; !state.enabled -> "معطل"; state.error != null -> state.error
        state.lastFullAt == 0L -> "جارٍ إعداد النسخة الأولية"; pending > 0 -> "تغييرات تنتظر الترحيل"; else -> "آخر كتابة متحقق منها — التغييرات مرحّلة" },
        color = if (state?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    Text("آخر نسخة كاملة: ${backupTime(state?.lastFullAt ?: 0)}")
    Text("آخر تغيير منسوخ: ${backupTime(state?.lastChangeAt ?: 0)}")
    Text("التغييرات المعلقة: $pending • حجم النسخ: ${backupSize(state?.bytes ?: 0)}")
    state?.treeUri?.let { uri ->
        val path = runCatching { android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(uri)).substringAfter(':') }.getOrDefault("Raad Pharmacy Backups")
        Text("المجلد: $path", style = MaterialTheme.typography.bodySmall)
    }
}
private fun backupTime(time: Long) = if (time <= 0) "لم تُنشأ بعد" else "${formatDate(time)} • ${formatTime(time)}"
private fun backupSize(bytes: Long) = java.lang.String.format(java.util.Locale.US, "%.2f MB", bytes / 1048576.0)
private fun destinationLabel(id: String) = when (id) { LocalBackupEngine.PRIVATE -> "خاصة"; LocalBackupEngine.SD -> "SD"; else -> "الهاتف" }

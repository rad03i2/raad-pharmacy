package com.radwan.raadpharmacy.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.backup.*
import com.radwan.raadpharmacy.cloud.CloudSyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LegacyBackupRestoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { LocalBackupEngine.get(context) }
    val scope = rememberCoroutineScope()
    val destinations by engine.destinations.collectAsStateWithLifecycle(initialValue = emptyList())
    val sequence by engine.sequence.collectAsStateWithLifecycle(initialValue = 0L)
    val engineBusy by engine.busy.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(BackupPage.RESTORE) }
    var working by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var held by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<BackupHistoryItem>()) }
    var pickingSd by rememberSaveable { mutableStateOf(false) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var savedCheck by remember { mutableStateOf(false) }
    var keyNext by remember { mutableStateOf<BackupAction?>(null) }
    var importMode by rememberSaveable { mutableStateOf<String?>(null) }
    var importCode by rememberSaveable { mutableStateOf("") }
    var importUsesOwnKey by rememberSaveable { mutableStateOf(true) }
    var importFromOtherPhone by rememberSaveable { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<RestoredArchive?>(null) }
    var reconcileConfirm by remember { mutableStateOf(false) }
    val busy = working || engineBusy || pickerOpen || !loaded

    suspend fun refreshLocal() {
        val needsHistory = page == BackupPage.HISTORY
        val info = withContext(Dispatchers.IO) {
            Triple(engine.held(), engine.recoveryConfirmed(), if (needsHistory) engine.history() else emptyList())
        }
        held = info.first; confirmed = info.second; history = info.third; loaded = true
    }
    // Latch before launching, so rapid taps cannot start two operations or pickers.
    fun act(after: (() -> Unit)? = null, block: suspend () -> String?) {
        if (working) return
        working = true
        scope.launch {
            var completed = false
            try {
                block()?.let { message = it }
                completed = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = backupErrorMessage(error) }
            finally {
                try { refreshLocal() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "تعذر تحديث الحالة. افتح الشاشة مجددًا للتحقق من النسخ." }
                working = false
            }
            if (completed) after?.invoke()
        }
    }
    LaunchedEffect(destinations, sequence, engineBusy, page) {
        try { refreshLocal() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loaded = true; message = "تعذر قراءة سجل النسخ. حاول فتح الشاشة مجددًا." }
    }
    suspend fun destinationResult(id: String): String {
        val states = engine.destinations.first()
        return backupWriteResult(states.filter { it.id == id }, engine.sequence.first())
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pickerOpen = false
        if (uri != null) act {
            engine.configureFolder(uri, pickingSd)
            destinationResult(if (pickingSd) LocalBackupEngine.SD else LocalBackupEngine.SHARED)
        }
    }
    val restoreFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pickerOpen = false
        if (uri != null) act {
            try {
                val key = if (importUsesOwnKey) withContext(Dispatchers.IO) { engine.recoveryCode() } else importCode
                preview = engine.previewFolder(uri, key); null
            } finally { importCode = "" }
        }
        else importCode = ""
    }
    val restoreFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickerOpen = false
        if (uri != null) act {
            try {
                val key = if (importUsesOwnKey) withContext(Dispatchers.IO) { engine.recoveryCode() } else importCode
                preview = engine.previewFile(uri, key); null
            } finally { importCode = "" }
        }
        else importCode = ""
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        pickerOpen = false
        if (uri != null) act { engine.exportSnapshot(uri); "تم حفظ نسخة مشفرة والتحقق من سلامتها." }
    }
    fun launchExternal(action: BackupAction) {
        if (pickerOpen) return
        pickerOpen = true
        when (action) {
            BackupAction.PHONE_FOLDER -> { pickingSd = false; folderPicker.launch(null) }
            BackupAction.SD_FOLDER -> { pickingSd = true; folderPicker.launch(null) }
            BackupAction.EXPORT -> exportFile.launch("RaadPharmacy-${System.currentTimeMillis()}.rpb")
            else -> pickerOpen = false
        }
    }
    fun showRecovery(next: BackupAction? = null) {
        act {
            code = withContext(Dispatchers.IO) { engine.recoveryCode() }
            savedCheck = false; keyNext = next; showKey = true; null
        }
    }
    fun external(action: BackupAction) {
        if (working || pickerOpen) return
        if (confirmed) launchExternal(action) else showRecovery(action)
    }
    fun back() {
        if (working) return
        page = when (page) {
            BackupPage.HOME, BackupPage.RESTORE -> { onBack(); return }
            BackupPage.HISTORY -> BackupPage.RESTORE
            else -> BackupPage.HOME
        }
    }
    if (page == BackupPage.CLOUD) {
        CentralCloudBackupScreen { page = BackupPage.HOME }
    } else {
        BackHandler(onBack = ::back)
        StorageBackupContent(page, destinations, sequence, busy, confirmed, held, history, ::back,
            onPage = { if (!working) page = it }, onAction = { action ->
                when (action) {
                    BackupAction.BACKUP -> act {
                        val success = engine.process(force = true)
                        val result = backupWriteResult(engine.destinations.first(), engine.sequence.first())
                        if (!success && !result.startsWith("تعذر")) "لم يكتمل النسخ إلى جميع الأماكن. افتح المكان الذي يحتاج مراجعة." else result
                    }
                    BackupAction.PHONE_FOLDER, BackupAction.SD_FOLDER, BackupAction.EXPORT -> external(action)
                    BackupAction.KEY -> showRecovery()
                    BackupAction.SD_TOGGLE -> {
                        val state = destinations.firstOrNull { it.id == LocalBackupEngine.SD }
                        if (state == null) external(BackupAction.SD_FOLDER)
                        else if (!state.enabled && !confirmed) showRecovery(BackupAction.SD_FOLDER)
                        else act {
                            engine.setSdEnabled(!state.enabled)
                            if (state.enabled) "تم إيقاف النسخ إلى البطاقة. النسخ السابقة باقية." else destinationResult(LocalBackupEngine.SD)
                        }
                    }
                    BackupAction.IMPORT_FOLDER, BackupAction.IMPORT_FILE -> {
                        importCode = ""; importError = null; importFromOtherPhone = false
                        importMode = if (action == BackupAction.IMPORT_FOLDER) "folder" else "file"
                    }
                    BackupAction.RECONCILE -> reconcileConfirm = true
                }
            }, onPreview = { item -> act { preview = engine.previewHistory(item); null } })
    }
    if (showKey) AlertDialog(
        onDismissRequest = { if (!working) { showKey = false; code = ""; keyNext = null } },
        title = { Text("احفظ مفتاح الاسترداد") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("تحتاج هذا المفتاح لفتح النسخ على هاتف آخر. احفظه خارج الهاتف ولا تشاركه مع الآخرين.")
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    SelectionContainer { Text(code, style = MaterialTheme.typography.bodyMedium) }
                }
                TextButton(onClick = {
                    val clip = ClipData.newPlainText("مفتاح الاسترداد", code)
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        clip.description.extras = android.os.PersistableBundle().apply {
                            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                    }
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                }) { Text("نسخ المفتاح") }
                Row { Checkbox(savedCheck, onCheckedChange = { savedCheck = it }); Text("حفظته في مكان آمن خارج الهاتف", Modifier.padding(top = 12.dp).weight(1f)) }
            }
        }, confirmButton = { TextButton(enabled = savedCheck && !working, onClick = {
            val next = keyNext
            act(after = { next?.let(::launchExternal) }) {
                withContext(Dispatchers.IO) { engine.confirmRecovery() }
                confirmed = true; showKey = false; code = ""
                keyNext = null
                if (next == null) "تم تأكيد حفظ المفتاح." else null
            }
        }) { Text(if (keyNext == null) "تأكيد الحفظ" else "حفظ ومتابعة") } },
        dismissButton = { TextButton(enabled = !working, onClick = { showKey = false; code = ""; keyNext = null }) { Text("إلغاء") } }
    )
    importMode?.let { mode -> AlertDialog(
        onDismissRequest = { importMode = null; importCode = "" }, title = { Text("فتح نسخة احتياطية") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (mode == "folder") "اختر مجلد Raad Pharmacy Backups. تُستعاد النسخة مع التغييرات التي تلتها."
                    else "اختر ملف النسخة المشفرة أو نسخة JSON قديمة.")
                Row {
                    Checkbox(importFromOtherPhone, onCheckedChange = { importFromOtherPhone = it; importError = null })
                    Text("النسخة من هاتف آخر أو تثبيت سابق", Modifier.padding(top = 12.dp).weight(1f))
                }
                if (!importFromOtherPhone) Text("سيُستخدم مفتاح هذا التطبيق تلقائيًا.", style = MaterialTheme.typography.bodySmall)
                if (importFromOtherPhone) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(importCode, onValueChange = { importCode = it; importError = null }, label = { Text("مفتاح الاسترداد") },
                        isError = importError != null, modifier = Modifier.fillMaxWidth())
                }
                if (importFromOtherPhone && mode == "file") Text("نسخة JSON القديمة لا تحتاج مفتاحًا.", style = MaterialTheme.typography.bodySmall)
                importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = {
            if (importFromOtherPhone && (mode == "folder" || importCode.isNotBlank()) && runCatching { BackupCrypto.parseCode(importCode) }.isFailure) {
                importError = "المفتاح غير كامل. الصق مفتاح الاسترداد الذي حفظته عند إنشاء النسخة."
            } else {
                importUsesOwnKey = !importFromOtherPhone; importMode = null; pickerOpen = true
                if (mode == "folder") restoreFolder.launch(null) else restoreFile.launch(arrayOf("*/*"))
            }
        }) { Text(if (mode == "folder") "اختيار المجلد" else "اختيار الملف") } },
        dismissButton = { TextButton(onClick = { importMode = null; importCode = "" }) { Text("إلغاء") } }
    ) }
    preview?.let { archive -> AlertDialog(
        onDismissRequest = { if (!working) { preview = null; importCode = "" } }, title = { Text("تأكيد الاستعادة") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${archive.ledger.customers.size} زبون • ${archive.ledger.entries.size} حركة")
                Text("تاريخ النسخة: ${backupTime(archive.ledger.createdAt)}")
                Text("ستستبدل النسخة بيانات هذا الهاتف. تُحفظ نسخة أمان أولًا، وتُوقف المزامنة والتعديل حتى مراجعة البيانات.")
                Text("لن تُرفع الديون القديمة إلى الحساب المركزي تلقائيًا.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(enabled = !working, onClick = {
            preview = null; importCode = ""; act { engine.restore(archive).message }
        }) { Text("استعادة على هذا الهاتف") } },
        dismissButton = { TextButton(enabled = !working, onClick = { preview = null; importCode = "" }) { Text("إلغاء") } }
    ) }
    if (reconcileConfirm) AlertDialog(onDismissRequest = { reconcileConfirm = false }, title = { Text("العودة إلى البيانات الحالية؟") },
        text = { Text("تُحفظ نسخة من بيانات الهاتف ثم تُحمّل بيانات الصيدلية الحالية من الحساب المركزي وتعود المزامنة. العمليات المعلقة تبقى محفوظة للمراجعة.") },
        confirmButton = { TextButton(enabled = !working, onClick = {
            reconcileConfirm = false; act { CloudSyncEngine(context).reconcileRestoredFromServer(); "تم تحميل البيانات الحالية واستئناف المزامنة." }
        }) { Text("تحميل البيانات الحالية") } }, dismissButton = { TextButton(onClick = { reconcileConfirm = false }) { Text("إلغاء") } })
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("النسخ الاحتياطي") },
        text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("حسنًا") } }) }
}

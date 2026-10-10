package com.radwan.raadpharmacy.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.identity.Identity
import com.radwan.raadpharmacy.backup.*
import com.radwan.raadpharmacy.cloud.CloudSyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StorageBackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { AutomaticBackupEngine.get(context) }
    val legacy = remember { LocalBackupEngine.get(context) }
    val state by engine.state.collectAsStateWithLifecycle()
    val sequence by engine.sequence.collectAsStateWithLifecycle(initialValue = 0L)
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(AutoBackupPage.HOME) }
    var working by remember { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<PortableBackupItem>()) }
    var preview by remember { mutableStateOf<RestoredArchive?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var oldScreen by rememberSaveable { mutableStateOf(false) }
    var reconcile by remember { mutableStateOf(false) }
    fun act(block: suspend () -> Unit) {
        if (working) return
        working = true
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = backupErrorMessage(error) }
            finally {
                try { held = withContext(Dispatchers.IO) { legacy.held() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Preserve the last observed restore hold. */ }
                working = false
            }
        }
    }
    LaunchedEffect(page, sequence, state.phone.updatedAt, state.sd.updatedAt, oldScreen) {
        held = withContext(Dispatchers.IO) { legacy.held() }
        if (page == AutoBackupPage.RESTORE && !oldScreen) history = engine.history()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        picker = false
        if (granted) act { engine.process(force = true) }
        else message = "السماح بحفظ الملفات مطلوب لإنشاء مجلد النسخ على هذا الإصدار من أندرويد."
    }
    // Modern Android creates its public Download folder without a broad storage permission.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            picker = true; permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    val sdPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        picker = false
        if (uri != null) act { engine.configureSd(uri) }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picker = false
        if (uri != null) act {
            val key = withContext(Dispatchers.IO) { legacy.recoveryCode() }
            preview = legacy.previewFile(uri, key)
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        picker = false
        if (uri != null) act { engine.export(uri); message = "تم حفظ ملف النسخة والتحقق من سلامته. يمكن استعادته دون مفتاح." }
    }
    val googleResult = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        picker = false
        if (result.resultCode == Activity.RESULT_OK) act {
            try {
                val authorization = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data)
                engine.connectDrive(checkNotNull(authorization.accessToken))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = AutomaticBackupEngine.driveError(error) }
        }
    }
    fun connectGoogle() {
        val activity = context.activity() ?: run { message = "تعذر فتح شاشة ربط الحساب."; return }
        if (working || picker) return
        working = true
        Identity.getAuthorizationClient(activity).authorize(DeviceDriveBackup.request(state.account))
            .addOnSuccessListener { result ->
                working = false
                if (result.hasResolution()) {
                    val pending = result.pendingIntent
                    if (pending == null) message = "تعذر فتح إذن Google. حاول مجددًا."
                    else {
                        picker = true
                        googleResult.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                    }
                } else act { engine.connectDrive(checkNotNull(result.accessToken)) }
            }
            .addOnFailureListener { error -> working = false; message = AutomaticBackupEngine.driveError(error) }
            .addOnCanceledListener { working = false }
    }
    fun back() {
        if (working || picker) return
        if (page == AutoBackupPage.HOME) onBack() else page = AutoBackupPage.HOME
    }
    if (oldScreen) LegacyBackupRestoreScreen { oldScreen = false }
    else {
        BackHandler(onBack = ::back)
        AutomaticBackupContent(page, state, sequence, working || picker, held, history, ::back,
            onPage = { page = it }, onAction = { action -> when (action) {
                AutoBackupAction.UPDATE -> {
                    if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        picker = true; permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else act { engine.process(force = true) }
                }
                AutoBackupAction.SD_FOLDER -> { picker = true; sdPicker.launch(null) }
                AutoBackupAction.SD_TOGGLE -> {
                    if (!state.sd.enabled && state.sd.location.isBlank()) { picker = true; sdPicker.launch(null) }
                    else act { engine.setSdEnabled(!state.sd.enabled) }
                }
                AutoBackupAction.GOOGLE_CONNECT -> connectGoogle()
                AutoBackupAction.GOOGLE_DISCONNECT -> act { engine.disconnectDrive() }
                AutoBackupAction.GOOGLE_RETRY -> act { engine.process(); engine.retryDrive() }
                AutoBackupAction.IMPORT -> { picker = true; filePicker.launch(arrayOf("*/*")) }
                AutoBackupAction.EXPORT -> { picker = true; export.launch("RaadPharmacy-${System.currentTimeMillis()}.raadbackup") }
                AutoBackupAction.CLOUD_HISTORY -> act {
                    try { history = engine.history(includeDrive = true) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { message = AutomaticBackupEngine.driveError(error) }
                }
                AutoBackupAction.LEGACY -> oldScreen = true
                AutoBackupAction.RECONCILE -> reconcile = true
            } }, onPreview = { item -> act { preview = engine.preview(item) } })
    }
    preview?.let { archive -> AlertDialog(onDismissRequest = { if (!working) preview = null },
        title = { Text("تأكيد الاستعادة") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${archive.ledger.customers.size} زبون • ${archive.ledger.entries.size} حركة")
                Text("تاريخ النسخة: ${backupTime(archive.ledger.createdAt)}")
                Text("ستستبدل بيانات هذا الهاتف. تُحفظ نسخة أمان قبل الاستعادة، وتُوقف المزامنة والتعديل حتى مراجعتها.")
            }
        }, confirmButton = { TextButton(enabled = !working, onClick = {
            preview = null
            act { message = legacy.restore(archive).message; engine.wake() }
        }) { Text("استعادة النسخة") } }, dismissButton = { TextButton(enabled = !working, onClick = { preview = null }) { Text("إلغاء") } }) }
    if (reconcile) AlertDialog(onDismissRequest = { reconcile = false }, title = { Text("العودة إلى بيانات الصيدلية الحالية؟") },
        text = { Text("تُحفظ نسخة أمان ثم تُحمّل البيانات الحالية من الحساب المركزي وتعود المزامنة.") },
        confirmButton = { TextButton(enabled = !working, onClick = {
            reconcile = false
            act { CloudSyncEngine(context).reconcileRestoredFromServer(); engine.wake(); message = "تم تحميل البيانات الحالية واستئناف المزامنة." }
        }) { Text("تحميل البيانات الحالية") } }, dismissButton = { TextButton(onClick = { reconcile = false }) { Text("إلغاء") } })
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("النسخ الاحتياطي") },
        text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("حسنًا") } }) }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

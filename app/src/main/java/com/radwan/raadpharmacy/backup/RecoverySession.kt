package com.radwan.raadpharmacy.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.radwan.raadpharmacy.cloud.*
import com.radwan.raadpharmacy.customer.CustomerPhotoStore
import com.radwan.raadpharmacy.data.*
import com.radwan.raadpharmacy.ui.screens.backupErrorMessage
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

internal data class RecoveryUiState(val busy: Boolean = false, val progress: String = "",
    val plan: RecoveryPlan? = null, val message: String? = null, val completed: Boolean = false, val sourceName: String = "")

/** Application-owned file processing. Neither Activity recreation nor a lost URI grant discards the staged file. */
internal class RecoverySession private constructor(context: Context, daoOverride: PharmacyLedgerDao? = null) {
    private val app = context.applicationContext
    private val dao = daoOverride ?: PharmacyLedgerDatabase.get(app).dao()
    private val prefs = app.getSharedPreferences("raad_restore21_ui", Context.MODE_PRIVATE)
    private val folder = File(app.filesDir, "restore21").apply { mkdirs() }
    private val selected = File(folder, "selected.backup")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    private val mutable = MutableStateFlow(RecoveryUiState())
    val state = mutable.asStateFlow()
    private var archive: RestoredArchive? = null
    private fun fingerprint(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        selected.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private val importer = RecoveryImporter(dao) {
        mutable.value = mutable.value.copy(progress = "إنشاء نسخة الحماية والتحقق من سلامتها…")
        LocalBackupEngine.get(app).protectCurrentBeforeReconciliation()
    }
    init { val pendingUri = prefs.getString("uri", null)
        if (pendingUri != null) accept(Uri.parse(pendingUri)) else if (selected.isFile) reload() }
    fun hasSelection() = selected.isFile || prefs.contains("uri")
    fun picker(open: Boolean) { check(prefs.edit().putBoolean("picker", open).commit()) }
    fun pickerPending() = prefs.getBoolean("picker", false)
    fun cancelPicker() { picker(false) }
    fun problem(message: String) { mutable.value = mutable.value.copy(message = message) }
    private fun launch(force: Boolean = false, block: suspend () -> Unit) {
        if (mutable.value.busy && !force) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        scope.launch {
            operations.withLock {
            mutable.value = mutable.value.copy(busy = true)
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = mutable.value.copy(message = backupErrorMessage(error)) }
            catch (_: OutOfMemoryError) { mutable.value = mutable.value.copy(message = "النسخة أكبر من ذاكرة الهاتف الآمنة. بقيت البيانات الحالية سليمة؛ اختر نسخة أصغر أو هاتفًا بذاكرة أكبر.") }
            finally { mutable.value = mutable.value.copy(busy = false, progress = "") }
            }
        }
    }
    fun accept(uri: Uri) {
        picker(false)
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = runCatching { app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull() ?: uri.lastPathSegment.orEmpty().takeLast(80)
        val selection = java.util.UUID.randomUUID().toString()
        check(prefs.edit().putString("uri", uri.toString()).putString("name", name).putString("selection", selection).putBoolean("completed", false).commit())
        mutable.value = RecoveryUiState(busy = true, progress = "تجهيز الملف المختار…")
        launch(force = true) {
            archive = null
            mutable.value = RecoveryUiState(busy = true, progress = "نسخ الملف المختار إلى مساحة الاسترداد الآمنة…")
            val temporary = File(folder, "selected.tmp")
            try {
                val input = app.contentResolver.openInputStream(uri) ?: error("تعذر فتح الملف؛ أعد اختياره من مزود الملفات.")
                input.use { source -> FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        val count = source.read(buffer); if (count < 0) break
                        total += count
                        require(total <= BackupCrypto.MAX_FILE_BYTES) { "حجم النسخة يتجاوز الحد الآمن (128 ميغابايت)." }
                        output.write(buffer, 0, count)
                    }
                    require(total > 0) { "ملف النسخة فارغ." }
                    output.fd.sync()
                } }
                if (prefs.getString("selection", null) != selection) return@launch
                check(temporary.renameTo(selected)) { "تعذر تثبيت ملف الاسترداد؛ تحقق من مساحة الهاتف." }
                prefs.edit().remove("uri").commit()
                runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                read("")
            } finally { temporary.delete() }
        }
    }
    private suspend fun read(code: String) {
        check(!prefs.contains("uri")) { "لم يكتمل نسخ الملف المختار؛ أعد اختيار الملف. لم تُغيّر بيانات الهاتف." }
        mutable.value = mutable.value.copy(progress = "فحص الملف ومقارنة السجلات الفعلية…")
        require(selected.length() <= Runtime.getRuntime().maxMemory() / 8) { "حجم النسخة أكبر من ذاكرة المعالجة الآمنة على هذا الهاتف." }
        // Portable and legacy JSON files never depend on a device encryption key.
        val decoded = LocalBackupEngine.get(app).previewFile(Uri.fromFile(selected), code)
        if (prefs.contains("uri")) return // a newly delivered URI owns the next preview
        archive = decoded
        val result = dao.recoveryValue(RecoveryKeys.SESSION)?.let(::JSONObject)?.takeIf { it.optString("source") == fingerprint() }
        mutable.value = RecoveryUiState(busy = true, plan = importer.compare(decoded),
            completed = result != null, sourceName = prefs.getString("name", "").orEmpty(),
            message = result?.let { "نتيجة الدمج المحلي: أضيف " + it.getInt("added") + " • موجود " + it.getInt("existing") + " • متعارض " + it.getInt("conflicts") })
    }
    fun reload(code: String = "") = launch { read(code) }
    fun confirm() = launch {
        val chosen = checkNotNull(archive) { "اختر نسخة صالحة أولًا." }
        val plan = checkNotNull(mutable.value.plan)
        SupabaseProvider.client.auth.awaitInitialization()
        val owner = checkNotNull(SupabaseProvider.client.auth.currentSessionOrNull()?.user?.id) { "افتح الحساب أولًا؛ الملف المختار محفوظ." }
        val device = CloudDeviceStore(app)
        val pharmacy = checkNotNull(device.pushPharmacyId()) { "يلزم تهيئة الحساب مع السحابة مرة واحدة قبل الاسترداد؛ الملف محفوظ." }
        check(device.pushUserId() == owner) { "انتظر اكتمال تهيئة الحساب الحالي ثم أعد التأكيد." }
        val result = importer.confirm(chosen, plan.sequence, owner, pharmacy, fingerprint())
        check(prefs.edit().putBoolean("completed", true).commit())
        mutable.value = mutable.value.copy(completed = true, message = "اكتمل الدمج المحلي: أضيف " + result.additions +
            " • موجود " + result.existing + " • متعارض " + result.conflicts + ". تُفحص السجلات على الخادم؛ التعارضات لا تُرسل تلقائيًا.")
        // Durable rows precede scheduling. Startup repairs death between this commit and enqueue.
        RecoveryScheduler.enqueue(app)
        runCatching { CustomerPhotoStore(app).rebuildFromBackup(dao.backupPhotos()) }
        AutomaticBackupEngine.get(app).wake()
    }
    suspend fun stage(archive: RestoredArchive) = operations.withLock {
        check(!mutable.value.busy) { "تجري معالجة نسخة أخرى." }
        mutable.value = RecoveryUiState(busy = true, progress = "تجهيز المعاينة الآمنة…")
        val selection = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("selection", selection).putString("name", "نسخة من سجل الحماية").commit()
        try {
        val capture = BackupCapture(archive.ledger.customers.map { it.toEntity() }, archive.ledger.entries.map { it.toEntity() },
            archive.photos, archive.sequence, false)
        val bytes = PortableBackup.encode(capture, archive.pendingCloud, archive.ledger.createdAt)
        val temporary = File(folder, "selected.tmp")
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        check(prefs.getString("selection", null) == selection) { "وصل اختيار ملف آخر؛ ستظهر معاينته." }
        check(temporary.renameTo(selected))
        prefs.edit().remove("uri").putBoolean("completed", false).commit()
        read("")
        } finally { mutable.value = mutable.value.copy(busy = false, progress = "") }
    }
    companion object {
        @Volatile private var instance: RecoverySession? = null
        internal fun forTesting(context: Context, dao: PharmacyLedgerDao) = RecoverySession(context, dao)
        internal fun stopForTesting() { instance?.scope?.cancel(); instance = null }
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: RecoverySession(context).also { instance = it }
        }
    }
    internal fun closeForTesting() { scope.cancel() }
}

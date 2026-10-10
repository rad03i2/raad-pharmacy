package com.radwan.raadpharmacy.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.work.*
import com.google.android.gms.common.api.ApiException
import com.radwan.raadpharmacy.cloud.CloudSyncJournal
import com.radwan.raadpharmacy.data.PharmacyLedgerDao
import com.radwan.raadpharmacy.data.PharmacyLedgerDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Observes the existing durable watermark; never changes the ledger or its ordinary cloud sync. */
internal class AutomaticBackupEngine private constructor(context: Context,
    daoOverride: PharmacyLedgerDao? = null, private val phoneOverride: PortableBackupTarget? = null,
    private val sdOverride: PortableBackupTarget? = null, private val scheduling: Boolean = true,
    private val driveOverride: ((String) -> DeviceDriveBackup)? = null
) {
    private val app = context.applicationContext
    private val dao = daoOverride ?: PharmacyLedgerDatabase.get(app).dao()
    private val prefs = app.getSharedPreferences("raad_automatic_backup_v3", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val driveMutex = Mutex()
    private val wakes = Channel<Unit>(Channel.CONFLATED)
    private val cache = AtomicFile(File(app.noBackupFilesDir, "raad-portable-latest.backup"))
    private val device = prefs.getString("device", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device", it).commit()
    }
    private val _state = MutableStateFlow(readState())
    val state = _state.asStateFlow()
    val sequence = dao.observeBackupSequence()
    @Volatile private var started = false
    private fun readPlace(id: String, enabled: Boolean, location: String) = AutomaticBackupPlace(id, enabled,
        prefs.getLong("$id.sequence", -1), prefs.getLong("$id.updated", 0), prefs.getLong("$id.bytes", 0),
        location, prefs.getString("$id.error", null))
    private fun readState(): AutomaticBackupState {
        val account = prefs.getString("drive.account", null)
        return AutomaticBackupState(
            readPlace("phone", true, "Download/${PortableBackup.FOLDER}"),
            readPlace("sd", prefs.getBoolean("sd.enabled", false), prefs.getString("sd.tree", "").orEmpty()),
            readPlace("drive", account != null && prefs.getBoolean("drive.enabled", true), PortableBackup.FOLDER), account
        )
    }
    private fun refresh() {
        _state.update { readState().copy(busy = it.busy, uploading = it.uploading) }
    }
    private fun save(place: AutomaticBackupPlace, locator: String? = null) {
        val edit = prefs.edit().putLong("${place.id}.sequence", place.sequence)
            .putLong("${place.id}.updated", place.updatedAt).putLong("${place.id}.bytes", place.bytes)
            .putString("${place.id}.error", place.error)
        if (place.id in listOf("phone", "sd") && place.error == null && place.updatedAt > 0)
            edit.putBoolean("${place.id}.portableOwned", true)
        locator?.let { edit.putString("${place.id}.locator", it) }
        check(edit.commit()) { "تعذر حفظ حالة النسخة الاحتياطية." }
        refresh()
    }
    @Synchronized fun start() {
        if (started) return
        started = true
        if (scheduling) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_BAD_REMOVAL); addDataScheme("file")
            }
            runCatching { androidx.core.content.ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) { wake() }
            }, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED) }
            WorkManager.getInstance(app).enqueueUniquePeriodicWork("raad-portable-maintenance", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AutomaticBackupWorker>(15, TimeUnit.MINUTES).build())
        }
        scope.launch {
            // Adopt an existing SD grant. Old encrypted files remain available for recovery.
            if (!prefs.contains("sd.tree")) dao.backupDestinations().firstOrNull { it.id == LocalBackupEngine.SD }?.let { old ->
                old.treeUri?.let { check(prefs.edit().putString("sd.tree", it).putBoolean("sd.enabled", old.enabled).commit()); refresh() }
            }
            process(force = true)
            dao.observeBackupSequence().distinctUntilChanged().collect { wake() }
        }
        scope.launch { for (ignored in wakes) { delay(250); process() } }
        wake()
    }
    fun wake() {
        wakes.trySend(Unit)
        if (!scheduling) return
        WorkManager.getInstance(app).enqueueUniqueWork("raad-portable-drain", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutomaticBackupWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    private fun wakeDrive(replace: Boolean = false) {
        if (!scheduling || !_state.value.drive.enabled) return
        WorkManager.getInstance(app).enqueueUniqueWork("raad-drive-mirror", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DeviceDriveBackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    private fun phone() = phoneOverride ?: PhoneBackupTarget(app)
    private suspend fun drive(account: String) = driveOverride?.invoke(account) ?: DeviceDriveBackup.authorized(app, account)
    private fun sd(): PortableBackupTarget {
        sdOverride?.let { return it }
        val uri = Uri.parse(checkNotNull(prefs.getString("sd.tree", null)) { "اختر مجلد البطاقة مرة واحدة." })
        return CardBackupTarget(TreeBackupStorage(app, uri, DocumentsContract.getDocumentId(uri)))
    }
    private fun pending(): JSONObject {
        val p = CloudSyncJournal(app).snapshot()
        return JSONObject().put("customersUpsert", JSONArray(p.customerUpserts.toList()))
            .put("customersDelete", JSONArray(p.customerDeletes.toList()))
            .put("transactionsUpsert", JSONArray(p.transactionUpserts.toList()))
            .put("transactionsDelete", JSONArray(p.transactionDeletes.toList()))
    }
    private fun cached(): ByteArray? = runCatching { cache.openRead().use { it.readBackupBytes() }.also { PortableBackup.decode(it) } }.getOrNull()
    private fun cache(bytes: ByteArray) {
        val out = cache.startWrite()
        try { out.write(bytes); cache.finishWrite(out) }
        catch (error: Exception) { cache.failWrite(out); throw error }
        check(cached()?.contentEquals(bytes) == true) { "تعذر التحقق من ملف النسخة." }
    }
    suspend fun process(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            _state.update { it.copy(busy = true) }
            try {
                val capture = dao.captureBackup()
                val previous = cached()
                val archive = previous?.let(PortableBackup::decode)
                val bytes = if (!force && archive?.sequence == capture.sequence) checkNotNull(previous)
                    else PortableBackup.encode(capture, pending(), maxOf(System.currentTimeMillis(), (archive?.ledger?.createdAt ?: 0) + 1)).also { cache(it) }
                val now = PortableBackup.decode(bytes).ledger.createdAt
                val name = PortableBackup.name(now, capture.sequence)
                var success = true
                for (id in listOf("phone", "sd")) {
                    val place = if (id == "phone") _state.value.phone else _state.value.sd
                    if (!place.enabled) continue
                    try {
                        val target = if (id == "phone") phone() else sd()
                        val available = target.list()
                        if (!force && place.sequence == capture.sequence && place.error == null && place.updatedAt > 0 &&
                            available.any { it.locator == prefs.getString("$id.locator", null) }) continue
                        // An interrupted attempt may already have a fully written file; verify before reusing it.
                        val existing = available.firstOrNull { it.name == name }
                        val locator = if (existing != null && runCatching { target.read(existing.locator).contentEquals(bytes) }.getOrDefault(false)) existing.locator
                            else target.write(name, bytes)
                        save(place.copy(sequence = capture.sequence, updatedAt = now, bytes = bytes.size.toLong(), error = null), locator)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { success = false; save(place.copy(error = localError(error, id))) }
                }
                wakeDrive()
                success
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // A backup/cache failure must never crash the working ledger application.
                _state.update { it.copy(phone = it.phone.copy(error = localError(error, "phone"))) }
                false
            } finally { _state.update { it.copy(busy = false) } }
        }
    }
    suspend fun configureSd(tree: Uri) = withContext(Dispatchers.IO) {
        TreeBackupStorage.validateLocalTree(app, tree, sd = true)
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        mutex.withLock {
            val root = TreeBackupStorage.createRepository(app, tree, PortableBackup.FOLDER)
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(root))
            check(prefs.edit().putString("sd.tree", uri.toString()).putBoolean("sd.enabled", true)
                .putLong("sd.sequence", -1).remove("sd.error").commit())
            refresh()
        }
        process(); wake()
    }
    suspend fun setSdEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!enabled || sdOverride != null || prefs.contains("sd.tree")) { "اختر مجلد البطاقة أولًا." }
            val edit = prefs.edit().putBoolean("sd.enabled", enabled)
            if (!enabled) edit.putBoolean("sd.portableOwned", true)
            check(edit.commit()); refresh()
        }
        if (enabled) { process(); wake() }
    }
    suspend fun connectDrive(token: String) = withContext(Dispatchers.IO) {
        val account = DeviceDriveBackup.forToken(app, token).email()
        driveMutex.withLock {
            check(prefs.edit().putString("drive.account", account).putBoolean("drive.enabled", true)
                .putLong("drive.sequence", -1).putLong("drive.updated", 0).remove("drive.error").commit())
            refresh()
        }
        process(); wakeDrive(replace = true)
    }
    suspend fun disconnectDrive() = withContext(Dispatchers.IO) {
        driveMutex.withLock {
            check(prefs.edit().remove("drive.account").putBoolean("drive.enabled", false).remove("drive.error").commit()); refresh()
        }
        if (scheduling) WorkManager.getInstance(app).cancelUniqueWork("raad-drive-mirror")
    }
    suspend fun uploadDrive(): Boolean = withContext(Dispatchers.IO) {
        driveMutex.withLock {
            val account = _state.value.account ?: return@withLock true
            if (!_state.value.drive.enabled) return@withLock true
            _state.update { it.copy(uploading = true) }
            try {
                val client = drive(account)
                // A bounded drain catches changes made while the preceding upload was running.
                repeat(3) {
                    val bytes = mutex.withLock { cached() } ?: return@withLock false
                    val archive = PortableBackup.decode(bytes)
                    val place = _state.value.drive
                    if (place.sequence != archive.sequence || place.error != null || place.updatedAt == 0L) {
                        val id = client.upload(device, PortableBackup.name(archive.ledger.createdAt, archive.sequence), bytes)
                        save(place.copy(sequence = archive.sequence, updatedAt = archive.ledger.createdAt, bytes = bytes.size.toLong(), error = null), id)
                    }
                    if (dao.latestBackupSequence() == archive.sequence) return@withLock true
                    process()
                }
                false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { save(_state.value.drive.copy(error = driveError(error))); false }
            finally { _state.update { it.copy(uploading = false) } }
        }
    }
    suspend fun history(includeDrive: Boolean = false): List<PortableBackupItem> = withContext(Dispatchers.IO) {
        val local = mutex.withLock { buildList {
            addAll(runCatching { phone().list() }.getOrDefault(emptyList()))
            if (_state.value.sd.enabled) addAll(runCatching { sd().list() }.getOrDefault(emptyList()))
        } }
        val cloud = if (includeDrive && _state.value.account != null) {
            drive(_state.value.account!!).history()
        } else emptyList()
        (local + cloud).sortedByDescending { it.createdAt }
    }
    suspend fun preview(item: PortableBackupItem): RestoredArchive = withContext(Dispatchers.IO) {
        val bytes = when (item.place) {
            "drive" -> drive(checkNotNull(_state.value.account)).download(item.locator)
            "sd" -> mutex.withLock { sd().read(item.locator) }
            else -> mutex.withLock { phone().read(item.locator) }
        }
        PortableBackup.decode(bytes)
    }
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        process()
        mutex.withLock {
            val bytes = checkNotNull(cached())
            app.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: error("تعذر حفظ الملف.")
            val saved = app.contentResolver.openInputStream(uri)?.use { it.readBackupBytes() } ?: error("تعذر التحقق من الملف.")
            check(saved.contentEquals(bytes)); PortableBackup.decode(saved)
        }
    }
    fun retryDrive() { wakeDrive(replace = true) }
    internal fun stopForTesting() { check(!scheduling); scope.cancel() }
    companion object {
        @Volatile private var instance: AutomaticBackupEngine? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: AutomaticBackupEngine(context).also { instance = it } }
        internal fun forTesting(context: Context, dao: PharmacyLedgerDao, phone: PortableBackupTarget, sd: PortableBackupTarget? = null,
            drive: ((String) -> DeviceDriveBackup)? = null) = AutomaticBackupEngine(context, dao, phone, sd, false, drive)
        private fun localError(error: Exception, id: String) = if (error.message?.contains(Regex("[ء-ي]")) == true) error.message!!.take(220)
            else if (id == "sd") "البطاقة غير متاحة أو فُقد الإذن. ستتحدث النسخة عند توفرها."
            else "تعذر حفظ نسخة الهاتف. تحقق من الإذن والمساحة المتاحة."
        internal fun driveError(error: Exception): String = when {
            error is ApiException && error.statusCode == 10 -> "إعداد تسجيل Google للتطبيق غير مكتمل. يلزم تفعيله بواسطة المطوّر."
            error is ApiException && error.statusCode == 16 -> "أُلغي ربط الحساب. يمكنك المحاولة مجددًا."
            error.message?.contains(Regex("[ء-ي]")) == true -> error.message!!.take(220)
            else -> "بانتظار الاتصال أو إذن Google. ستُعاد المحاولة تلقائيًا."
        }
    }
}

class AutomaticBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val engine = AutomaticBackupEngine.get(applicationContext)
        var complete = false
        repeat(3) {
            if (!complete) {
                val success = engine.process()
                val latest = PharmacyLedgerDatabase.get(applicationContext).dao().latestBackupSequence()
                val state = engine.state.value
                complete = success && state.phone.sequence == latest && (!state.sd.enabled || state.sd.sequence == latest)
            }
        }
        if (complete) Result.success() else Result.retry()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}
class DeviceDriveBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (AutomaticBackupEngine.get(applicationContext).uploadDrive()) Result.success() else Result.retry()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}

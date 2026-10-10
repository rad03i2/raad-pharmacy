package com.radwan.raadpharmacy.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.*
import com.radwan.raadpharmacy.cloud.pendingMutations
import com.radwan.raadpharmacy.cloud.CloudSyncEngine
import com.radwan.raadpharmacy.customer.CustomerPhotoStore
import com.radwan.raadpharmacy.data.*
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

/** Serial IO engine. SQLite is the durable queue; channel only accelerates already committed work. */
class LocalBackupEngine private constructor(context: Context, daoOverride: PharmacyLedgerDao? = null,
    private val keyOverride: ByteArray? = null, private val schedule: Boolean = true,
    private val storageOverride: ((BackupDestinationEntity) -> BackupStorage)? = null) {
    private val app = context.applicationContext
    private val dao = daoOverride ?: PharmacyLedgerDatabase.get(app).dao()
    private val keys = BackupKeyStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val checkedChains = mutableSetOf<String>()
    private val wakes = Channel<Unit>(Channel.CONFLATED)
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val destinations = dao.observeBackupDestinations()
    val sequence = dao.observeBackupSequence()
    @Volatile private var started = false

    @Synchronized fun start() {
        if (started) return
        started = true
        runCatching {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addDataScheme("file")
            }
            androidx.core.content.ContextCompat.registerReceiver(app, object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { wake() }
            }, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        WorkManager.getInstance(app).enqueueUniquePeriodicWork("raad-local-backup-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LocalBackupWorker>(3, TimeUnit.HOURS).build())
        scope.launch { dao.observeBackupSequence().collect { wake() } }
        scope.launch {
            for (ignored in wakes) {
                delay(250) // Coalesce bursts; committed journal survives death during this delay.
                process()
            }
        }
        wake()
    }
    fun wake() {
        if (!schedule) return
        wakes.trySend(Unit)
        // Persisted fallback survives process death/reboot. No internet constraint.
        WorkManager.getInstance(app).enqueueUniqueWork("raad-local-backup-drain", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LocalBackupWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    private fun activeKey(): ByteArray = keyOverride ?: keys.key()
    private fun portableOwns(id: String) = schedule && id in listOf(SHARED, SD) &&
        app.getSharedPreferences("raad_automatic_backup_v3", Context.MODE_PRIVATE).getBoolean("$id.portableOwned", false)
    fun recoveryCode() = BackupCrypto.recoveryCode(activeKey())
    fun recoveryConfirmed() = keys.confirmed()
    fun confirmRecovery() = keys.confirm()
    suspend fun held() = dao.restoreHold() == "1"

    suspend fun configureFolder(uri: Uri, sd: Boolean) = withContext(Dispatchers.IO) {
        require(keys.confirmed()) { "احفظ مفتاح الاسترداد وأكد حفظه أولًا." }
        TreeBackupStorage.validateLocalTree(app, uri, sd)
        app.contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        mutex.withLock {
            val root = TreeBackupStorage.createRepository(app, uri)
            // Access remains covered by the ancestor persisted tree grant. Retain that tree in the URI.
            val doc = android.provider.DocumentsContract.getTreeDocumentId(root)
            val repositoryUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, doc)
            val state = BackupDestinationEntity(if (sd) SD else SHARED, treeUri = repositoryUri.toString())
            val store = storage(state)
            writeVerified(store, "Recovery", "repository.rpi", JSONObject().put("marker", state.markerId))
            dao.saveBackupDestination(state)
        }
        process(force = true)
    }
    suspend fun setSdEnabled(enabled: Boolean) {
        mutex.withLock {
            val state = dao.backupDestinations().firstOrNull { it.id == SD } ?: error("حدد مجلد بطاقة SD أولًا.")
            dao.saveBackupDestination(state.copy(enabled = enabled))
        }
        if (enabled) { process(); wake() }
    }
    private fun storage(state: BackupDestinationEntity): BackupStorage = storageOverride?.invoke(state) ?:
        if (state.id == PRIVATE) PrivateBackupStorage(File(app.filesDir, "local_backups_v2"))
        else {
            val saved = Uri.parse(checkNotNull(state.treeUri))
            val rootId = android.provider.DocumentsContract.getDocumentId(saved)
            // Root is a subdirectory; retain permission tree id as well as target document id.
            TreeBackupStorage(app, saved, rootId)
        }
    private suspend fun pending(): JSONObject {
        val journal = dao.cloudOutbox().pendingMutations()
        return JSONObject().put("customerUpserts", JSONArray(journal.customerUpserts.toList()))
            .put("customerDeletes", JSONArray(journal.customerDeletes.toList()))
            .put("transactionUpserts", JSONArray(journal.transactionUpserts.toList()))
            .put("transactionDeletes", JSONArray(journal.transactionDeletes.toList()))
    }
    private fun writeVerified(store: BackupStorage, area: String, name: String, document: JSONObject): Long {
        val key = activeKey()
        val bytes = BackupCrypto.seal(document.toString().toByteArray(Charsets.UTF_8), key)
        store.write(area, name, bytes)
        val read = store.read(area, name)
        check(bytes.contentEquals(read)) { "لم تكتمل كتابة النسخة." }
        check(String(BackupCrypto.open(read, key), Charsets.UTF_8) == document.toString())
        return bytes.size.toLong()
    }
    private fun read(store: BackupStorage, area: String, name: String, key: ByteArray = activeKey()): JSONObject =
        JSONObject(String(BackupCrypto.open(store.read(area, name), key), Charsets.UTF_8))

    suspend fun process(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            _busy.value = true
            try {
                if (dao.backupDestinations().none { it.id == PRIVATE }) {
                    val state = BackupDestinationEntity(PRIVATE)
                    val store = storage(state)
                    // An orphan marker (crash before state commit) is never silently adopted.
                    val marker = store.list("Recovery").firstOrNull { it.name == "repository.rpi" }
                    if (marker != null) store.remove("Recovery", marker.name)
                    writeVerified(store, "Recovery", "repository.rpi", JSONObject().put("marker", state.markerId))
                    dao.saveBackupDestination(state)
                }
                var success = true
                for (original in dao.backupDestinations().sortedBy { if (it.id == PRIVATE) 0 else 1 }) {
                    if (!original.enabled || portableOwns(original.id)) continue
                    var state = original
                    try {
                        val store = storage(state)
                        check(read(store, "Recovery", "repository.rpi").getString("marker") == state.markerId) {
                            "هوية مجلد النسخ مختلفة. أعد اختيار الوجهة."
                        }
                        val checkId = state.id + ":" + state.chainId
                        if (state.lastFullAt > 0 && checkId !in checkedChains) {
                            try {
                                val recovered = loadRepository(store, activeKey(), chainId = state.chainId)
                                check(recovered.sequence >= state.cursor) { "نهاية سلسلة النسخ مفقودة." }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                // Correct marker was verified above: fork an independent full point in
                                // this SAME repository. Never adopt another card or overwrite old files.
                                state = state.copy(chainId = UUID.randomUUID().toString(), cursor = 0,
                                    snapshotSequence = 0, lastFullAt = 0, lastChangeAt = 0)
                                dao.saveBackupDestination(state)
                            }
                        }
                        val now = System.currentTimeMillis()
                        val latest = dao.latestBackupSequence()
                        // Drain incremental queue FIRST; a later snapshot must not discard a missing destination's queue.
                        if (state.lastFullAt > 0) {
                            while (state.cursor < latest) {
                                val rows = dao.backupChanges(state.cursor, 50).filter { it.sequence <= latest }
                                check(rows.isNotEmpty() && rows.first().sequence == state.cursor + 1) {
                                    "سجل التغييرات غير مكتمل؛ يلزم إنشاء نقطة استعادة جديدة."
                                }
                                val document = BackupArchive.changes(rows, state.chainId, pending())
                                val added = commit(store, document)
                                state = state.copy(cursor = rows.last().sequence, lastChangeAt = now, bytes = state.bytes + added, error = null)
                                dao.saveBackupDestination(state)
                                yield()
                            }
                        }
                        val due = state.lastFullAt == 0L || force ||
                            (latest > state.snapshotSequence && now - state.lastFullAt >= SIX_HOURS) ||
                            java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate() !=
                                java.time.Instant.ofEpochMilli(state.lastFullAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        if (due) {
                            val capture = dao.captureBackup()
                            // A capture may include writes committed while the first drain was running.
                            // Export those events too, so retained older snapshots keep a continuous chain.
                            if (state.lastFullAt > 0) {
                                while (state.cursor < capture.sequence) {
                                    val rows = dao.backupChanges(state.cursor, 50).filter { it.sequence <= capture.sequence }
                                    check(rows.isNotEmpty() && rows.first().sequence == state.cursor + 1)
                                    val addedChanges = commit(store, BackupArchive.changes(rows, state.chainId, pending()))
                                    state = state.copy(cursor = rows.last().sequence, lastChangeAt = now, bytes = state.bytes + addedChanges)
                                    dao.saveBackupDestination(state)
                                }
                            }
                            val document = BackupArchive.snapshot(capture, state.chainId, pending(), now)
                            BackupArchive.restore(document) // Validate ledger + photos before announcing success.
                            val added = commit(store, document)
                            state = state.copy(cursor = capture.sequence, snapshotSequence = capture.sequence,
                                lastFullAt = now, lastChangeAt = now, bytes = state.bytes + added, error = null)
                            dao.saveBackupDestination(state)
                            retain(store, state)
                            state = state.copy(bytes = totalBytes(store))
                        }
                        dao.saveBackupDestination(state.copy(error = null))
                        checkedChains.add(state.id + ":" + state.chainId)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) {
                        success = false
                        checkedChains.remove(state.id + ":" + state.chainId)
                        val detail = if (state.id == SD) "بطاقة الذاكرة غير متاحة أو تعذر النسخ: " else "تعذر النسخ: "
                        dao.saveBackupDestination(state.copy(error = detail + (e.message ?: "تحقق من الإذن والمساحة.")))
                    }
                }
                // Keep the authoritative sequence counter, and never prune an unavailable destination's queue.
                val all = dao.backupDestinations()
                val minimum = all.filterNot { portableOwns(it.id) }.minOfOrNull { it.cursor } ?: 0
                if (minimum > 0) dao.pruneBackupChanges(minimum)
                success
            } finally { _busy.value = false }
        }
    }
    private fun commit(store: BackupStorage, doc: JSONObject): Long {
        val id = UUID.randomUUID().toString()
        val area = if (doc.getString("type") == "SNAPSHOT") "Snapshots" else "Changes"
        val name = "$id.rpb"
        val bytes = writeVerified(store, area, name, doc)
        val sha = BackupCrypto.hash(store.read(area, name))
        val metadata = JSONObject().put("area", area).put("file", name).put("sha256", sha)
            .put("chain", doc.getString("chain")).put("type", doc.getString("type"))
            .put("sequence", doc.getLong("sequence")).put("from", doc.optLong("from", doc.getLong("sequence")))
            .put("createdAt", doc.getLong("createdAt"))
        return bytes + writeVerified(store, "Metadata", "${doc.getString("chain")}--$id.rpc", metadata)
    }
    private fun totalBytes(store: BackupStorage) = listOf("Snapshots", "Changes", "Metadata", "Recovery")
        .sumOf { area -> store.list(area).sumOf { it.bytes } }

    private fun committed(store: BackupStorage, key: ByteArray = activeKey(), chain: String? = null,
        tolerateUnreadable: Boolean = false): List<Pair<String, JSONObject>> = buildList {
        for (file in store.list("Metadata")) {
            if (!file.name.endsWith(".rpc") || (chain != null && !file.name.startsWith("$chain--"))) continue
            try { add(file.name to read(store, "Metadata", file.name, key)) }
            catch (e: Exception) { if (!tolerateUnreadable) throw e }
        }
    }

    private fun retain(store: BackupStorage, state: BackupDestinationEntity) {
        val files = committed(store, chain = state.chainId)
        val snapshots = files.filter { it.second.getString("type") == "SNAPSHOT" }.sortedByDescending { it.second.getLong("createdAt") }
        val keep = BackupRetention.keep(snapshots.map { it.first to it.second.getLong("createdAt") })
        if (keep.isEmpty()) return
        val oldest = snapshots.filter { it.first in keep }.minOf { it.second.getLong("sequence") }
        // Validate the newest full snapshot and its chain before deleting anything.
        loadRepository(store, activeKey(), snapshots.first().first)
        for ((name, meta) in files) {
            val remove = if (meta.getString("type") == "SNAPSHOT") name !in keep else meta.getLong("sequence") <= oldest
            if (remove) {
                store.remove("Metadata", name) // Remove visibility first; payload can be left harmlessly on failure.
                store.remove(meta.getString("area"), meta.getString("file"))
            }
        }
    }

    suspend fun history(): List<BackupHistoryItem> = withContext(Dispatchers.IO) {
        mutex.withLock { buildList {
            for (state in dao.backupDestinations()) {
                if (!state.enabled) continue
                try {
                    val store = storage(state)
                    for ((name, meta) in committed(store, tolerateUnreadable = true)) {
                        if (meta.getString("type") == "SNAPSHOT") add(BackupHistoryItem(state.id, name, meta.getLong("createdAt"),
                            store.list("Snapshots").firstOrNull { it.name == meta.getString("file") }?.bytes ?: 0))
                    }
                } catch (_: Exception) { /* Destination status reports inaccessible repositories. */ }
            }
        }.sortedByDescending { it.createdAt } }
    }
    private fun loadRepository(store: BackupStorage, key: ByteArray, snapshotName: String? = null, chainId: String? = null): RestoredArchive {
        val chosen = if (snapshotName != null) read(store, "Metadata", snapshotName, key)
            else committed(store, key, chainId, tolerateUnreadable = true)
                .filter { it.second.getString("type") == "SNAPSHOT" }
                .maxByOrNull { it.second.getLong("createdAt") }?.second
                    ?: error("المجلد لا يحتوي نسخة كاملة سليمة.")
        val chain = chosen.getString("chain")
        // Do not let corruption in an older, superseded chain hide a newer independent full point.
        val files = committed(store, key, chain)
        val sequence = chosen.getLong("sequence")
        val parts = files.filter { (_, m) -> m.getString("chain") == chain && m.getString("type") == "CHANGES" && m.getLong("sequence") > sequence }
        fun payload(meta: JSONObject): JSONObject {
            val area = meta.getString("area"); val file = meta.getString("file")
            require(area == "Snapshots" || area == "Changes")
            require(file.matches(Regex("[a-zA-Z0-9._-]+\\.rpb")))
            val raw = store.read(area, file)
            check(BackupCrypto.hash(raw) == meta.getString("sha256")) { "ملف النسخة تالف أو ناقص." }
            val doc = JSONObject(String(BackupCrypto.open(raw, key), Charsets.UTF_8))
            require(doc.getString("chain") == meta.getString("chain") && doc.getLong("sequence") == meta.getLong("sequence") && doc.getString("type") == meta.getString("type"))
            return doc
        }
        return BackupArchive.restore(payload(chosen), parts.map { payload(it.second) }, maxOf(sequence, files.filter { it.second.getString("chain") == chain }.maxOfOrNull { it.second.getLong("sequence") } ?: sequence))
    }
    suspend fun previewHistory(item: BackupHistoryItem): RestoredArchive = withContext(Dispatchers.IO) {
        mutex.withLock { val state = dao.backupDestinations().single { it.id == item.destination }
            loadRepository(storage(state), activeKey(), item.name) }
    }
    suspend fun previewFolder(uri: Uri, code: String): RestoredArchive = withContext(Dispatchers.IO) {
        require(uri.authority == "com.android.externalstorage.documents") { "اختر مجلد نسخ محليًا." }
        loadRepository(TreeBackupStorage(app, uri), BackupCrypto.parseCode(code))
    }
    suspend fun previewFile(uri: Uri, code: String): RestoredArchive = withContext(Dispatchers.IO) {
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBackupBytes() } ?: error("تعذر قراءة الملف.")
        if (PortableBackup.isPortable(bytes)) PortableBackup.decode(bytes)
        else if (BackupCrypto.isEncrypted(bytes)) {
            val doc = JSONObject(String(BackupCrypto.open(bytes, BackupCrypto.parseCode(code)), Charsets.UTF_8))
            BackupArchive.restore(doc) // Single full file is a standalone point; folder import applies subsequent segments.
        } else RestoredArchive(BackupValidator.parseValid(String(bytes, Charsets.UTF_8)), emptyList(), JSONObject(), 0)
    }
    suspend fun exportSnapshot(uri: Uri) = withContext(Dispatchers.IO) {
        require(keys.confirmed()) { "احفظ مفتاح الاسترداد أولًا." }
        mutex.withLock {
            val capture = dao.captureBackup()
            val doc = BackupArchive.snapshot(capture, UUID.randomUUID().toString(), pending(), System.currentTimeMillis())
            BackupArchive.restore(doc)
            val bytes = BackupCrypto.seal(doc.toString().toByteArray(Charsets.UTF_8), activeKey())
            app.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: error("تعذر تصدير النسخة.")
            val saved = app.contentResolver.openInputStream(uri)?.use { it.readBackupBytes() } ?: error("تعذر التحقق من النسخة.")
            check(saved.contentEquals(bytes))
            BackupCrypto.open(saved, activeKey())
        }
    }
    suspend fun protectCurrentBeforeReconciliation() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val capture = dao.captureBackup()
            val store = PrivateBackupStorage(File(app.filesDir, "local_backups_v2"))
            commit(store, BackupArchive.snapshot(capture, UUID.randomUUID().toString(), pending(), System.currentTimeMillis()))
        }
    }
    suspend fun restore(archive: RestoredArchive): BackupRestoreResult = withContext(Dispatchers.IO) {
        try {
            CloudSyncEngine.withLedgerSyncLock {
                mutex.withLock {
                    BackupValidator.validate(archive.ledger)
                    val capture = dao.captureBackup()
                    val store = PrivateBackupStorage(File(app.filesDir, "local_backups_v2"))
                    val safety = BackupArchive.snapshot(capture, UUID.randomUUID().toString(), pending(), System.currentTimeMillis())
                    commit(store, safety)
                    val quarantine = JSONObject().put("pendingCloud", archive.pendingCloud).put("previousPendingCloud", pending())
                    writeVerified(store, "Recovery", "quarantine-${UUID.randomUUID()}.rpi", quarantine)
                    dao.restoreLocal(archive.ledger.customers.map { it.toEntity() }, archive.ledger.entries.map { it.toEntity() }, archive.photos)
                    // Photos in Room are authoritative; filesystem mirror can safely retry after death.
                    runCatching { CustomerPhotoStore(app).rebuildFromBackup(archive.photos) }
                }
            }
            wake()
            BackupRestoreResult(true, "تمت الاستعادة محليًا. المزامنة موقوفة لحماية البيانات المركزية؛ راجع المصالحة من هذه الشاشة.", archive.ledger.customers.size, archive.ledger.entries.size)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { BackupRestoreResult(false, e.message ?: "تعذرت الاستعادة. لم يتم نشر البيانات إلى السحابة.") }
    }
    companion object {
        internal fun forTesting(context: Context, dao: PharmacyLedgerDao, key: ByteArray,
            storage: ((BackupDestinationEntity) -> BackupStorage)? = null) =
            LocalBackupEngine(context, dao, key, false, storage)
        const val PRIVATE = "private"
        const val SHARED = "phone"
        const val SD = "sd"
        const val SIX_HOURS = 6 * 60 * 60 * 1000L
        @Volatile private var instance: LocalBackupEngine? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: LocalBackupEngine(context).also { instance = it }
        }
    }
}
data class BackupHistoryItem(val destination: String, val name: String, val createdAt: Long, val bytes: Long)
class LocalBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (LocalBackupEngine.get(applicationContext).process()) Result.success() else Result.retry()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}

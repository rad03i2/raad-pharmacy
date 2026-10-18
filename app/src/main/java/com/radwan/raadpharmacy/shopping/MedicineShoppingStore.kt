package com.radwan.raadpharmacy.shopping

import android.content.Context
import com.radwan.raadpharmacy.suppliers.*
import androidx.work.*
import com.radwan.raadpharmacy.cloud.CloudDeviceStore
import com.radwan.raadpharmacy.cloud.CloudUiEvents
import com.radwan.raadpharmacy.cloud.SupabaseProvider
import io.github.jan.supabase.SupabaseClient
import java.io.File
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class ShoppingUiState(val identity: ShoppingIdentity? = null, val items: List<ShoppingItem> = emptyList(),
    val pending: Int = 0, val syncing: Boolean = false, val lastSyncedAt: Long = 0, val error: String? = null,
    val trash: List<ShoppingTrash> = emptyList(),val suppliers: List<SupplierEntry> = emptyList())

/** Independent metadata queue + realtime. Never calls financial sync, notifications, Storage or backup upload. */
internal class MedicineShoppingStore internal constructor(context: Context,
    private val clientOverride: SupabaseClient? = null,
    root: File = File(context.noBackupFilesDir,"medicine-shopping"),
    preferenceName: String = "raad_medicine_shopping_v24",
    private val enqueueWork: () -> Unit = { ShoppingSyncScheduler.enqueue(context.applicationContext) }) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences(preferenceName,Context.MODE_PRIVATE)
    private fun clock(): Long = System.currentTimeMillis()+prefs.getLong("server-clock-offset",0)
    internal val local=ShoppingLocalStore(app,root,::clock)
    internal val photos=ShoppingPhotos(app,root)
    internal val suppliers=SupplierLocalStore(app,if(root==File(app.noBackupFilesDir,"medicine-shopping")) "supplier-ledger.db" else File(root,"suppliers.db").absolutePath)
    private val client get()=clientOverride ?: SupabaseProvider.client
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val started=AtomicBoolean(false)
    private val signals=Channel<Unit>(Channel.CONFLATED)
    private val networkMutex=Mutex()
    private val cacheMutex=Mutex()
    private val mutable=MutableStateFlow(ShoppingUiState())
    val state: StateFlow<ShoppingUiState> = mutable.asStateFlow()
    @Volatile var visible=false

    internal fun identity(): ShoppingIdentity? {
        val auth=(clientOverride ?: SupabaseProvider.initializedClientOrNull())?.auth
        val session=auth?.currentSessionOrNull()?.user?.id
        val offline=app.getSharedPreferences("raad_cloud_auth",Context.MODE_PRIVATE).getBoolean("has_offline_session",false)
        val status=auth?.sessionStatus?.value
        val user=session ?: if(offline && (auth==null || status is SessionStatus.Initializing || status is SessionStatus.RefreshFailure))
            CloudDeviceStore(app).pushUserId() else null
        user ?: return null
        val device=CloudDeviceStore(app)
        val pharmacy=prefs.getString("tenant:$user",null) ?: device.pushPharmacyId()?.takeIf { device.pushUserId()==user }
        return pharmacy?.let { ShoppingIdentity(user,it) }
    }
    private fun publishLocal(syncing: Boolean = mutable.value.syncing, error: String? = mutable.value.error, synced: Long = mutable.value.lastSyncedAt) {
        val identity=identity()
        val previous=mutable.value.takeIf { it.identity==identity }
        try {
            mutable.value=ShoppingUiState(identity,identity?.let(local::list).orEmpty(),identity?.let { local.pending(it).size+local.changes(it).size+suppliers.list(it).count {r->r.pending} } ?: 0,
                syncing && identity!=null, if(previous==null) 0 else synced, error.takeIf { identity!=null },identity?.let(local::trash).orEmpty(),identity?.let(suppliers::list).orEmpty())
        } catch(_: android.database.sqlite.SQLiteException) {
            mutable.value=(previous ?: ShoppingUiState(identity)).copy(syncing=false,
                error="تعذر فتح التخزين المحلي لقائمة الشراء؛ لم يُحذف الملف. أعد تشغيل التطبيق أو تواصل مع الدعم.")
        }

    }
    fun start() {
        if(!started.compareAndSet(false,true)) return
        scope.launch {
            try { cacheMutex.withLock { local.expire(); photos.reconcile(local); publishLocal(syncing=false,error=null) } }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { cacheMutex.withLock { publishLocal(syncing=false,error="تعذر فتح قائمة الشراء؛ أعد المحاولة.") } }
            for(ignored in signals) { sync() }
        }
        scope.launch {
            client.auth.awaitInitialization()
            client.auth.sessionStatus.collectLatest {
                cacheMutex.withLock { publishLocal(syncing=false,error=null) }
                val user=client.auth.currentSessionOrNull()?.user?.id ?: return@collectLatest
                supervisorScope {
                    val channel=client.channel("raad-shopping-$user")
                    val changes=channel.postgresChangeFlow<PostgresAction>("public") { table="medicine_shopping_signal" }
                    launch { changes.collect { request() } }
                    launch { runCatching { withTimeout(12_000L) { channel.subscribe(blockUntilSubscribed=true) } } }
                    request()
                    try {
                        while(isActive) {
                            delay(if(visible) 6_000L else 30_000L)
                            if(CloudUiEvents.isAppForeground()) request()
                        }
                    } finally { withContext(NonCancellable) { runCatching { withTimeout(3_000L) { channel.unsubscribe() } } } }
                }
            }
        }
        runCatching { ShoppingSyncScheduler.ensure(app) }
    }
    fun request() { signals.trySend(Unit) }
    suspend fun add(id: String,title: String,note: String,hasPhoto: Boolean,expected: ShoppingIdentity?=null,shareRequest: String?=null) = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val owner=checkNotNull(identity()) { "انتظر اكتمال الاتصال بحساب الصيدلية أولاً." }
            check(expected==null || owner==expected) {"تغيّر حساب الصيدلية؛ لم تُرسل المادة إلى حساب آخر."}
            if(shareRequest!=null && local.hasSharedCommit(owner,shareRequest,id)) {photos.discardDraft(id);return@withLock}
            check(title.trim().isNotEmpty() || hasPhoto) { "أضف اسم العلاج أو صورته." }
            val item=ShoppingItem(id,title.trim(),note.trim(),hasPhoto,System.currentTimeMillis(),"أنت",true)
            if(!local.alreadySaved(owner,item)) {
                if(hasPhoto) photos.attach(owner.pharmacy,id)
                try { local.add(owner,item,shareRequest) }
                catch(error: Exception) { if(hasPhoto) photos.file(owner.pharmacy,id).delete(); throw error }
            } else if(shareRequest!=null) local.add(owner,item,shareRequest)
            photos.discardDraft(id)
            publishLocal(error=null)
        }
        request(); runCatching { enqueueWork() }
    }
    suspend fun complete(id: String) = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val owner=checkNotNull(identity()) { "تعذر تحديد حساب الصيدلية." }
            local.complete(owner,id)
            photos.delete(owner.pharmacy,id)
            publishLocal(error=null)
        }
        request(); runCatching { enqueueWork() }
    }
    suspend fun purchase(id: String) = withContext(Dispatchers.IO) {
        cacheMutex.withLock {val owner=checkNotNull(identity()) {"تعذر تحديد حساب الصيدلية."}
            local.purchase(owner,id);publishLocal(error=null)}
        request();runCatching {enqueueWork()}
    }
    suspend fun restore(id: String) = withContext(Dispatchers.IO) {
        cacheMutex.withLock {val owner=checkNotNull(identity()) {"تعذر تحديد حساب الصيدلية."}
            local.restore(owner,id);publishLocal(error=null)}
        request();runCatching {enqueueWork()}
    }
    suspend fun addSupplier(row: SupplierEntry) = withContext(Dispatchers.IO) {
        cacheMutex.withLock {val owner=checkNotNull(identity()) {"انتظر اكتمال الاتصال بحساب الصيدلية أولاً."}
            suppliers.add(owner,row);publishLocal(error=null)}
        request();runCatching {enqueueWork()}
    }
    suspend fun refreshLocal() = withContext(Dispatchers.IO) {cacheMutex.withLock {local.expire();photos.reconcile(local);publishLocal()}}
    private fun verify(owner: ShoppingIdentity) {
        check(client.auth.currentSessionOrNull()?.user?.id==owner.owner) { "SHOPPING_SESSION_CHANGED" }
        check(identity()==owner) { "SHOPPING_SESSION_CHANGED" }
    }
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) { networkMutex.withLock {
        try {
            client.auth.awaitInitialization()
            val user=client.auth.currentSessionOrNull()?.user?.id ?: return@withLock false
            cacheMutex.withLock { publishLocal(syncing=true,error=null) }
            suspend fun snapshot(): ShoppingSnapshot = withTimeout(12_000L) {
                client.postgrest.rpc("medicine_shopping_context_v25").decodeAs<ShoppingSnapshot>()
            }
            val first=snapshot()
            check(first.owner==user && client.auth.currentSessionOrNull()?.user?.id==user) { "SHOPPING_SESSION_CHANGED" }
            check(prefs.edit().putString("tenant:$user",first.pharmacy).apply {
                if(first.serverTime>0) putLong("server-clock-offset",first.serverTime-System.currentTimeMillis())
            }.commit())
            val active=ShoppingIdentity(user,first.pharmacy)
            cacheMutex.withLock { verify(active); local.merge(active,first); suppliers.merge(active,first.suppliers);local.expire(); photos.reconcile(local); publishLocal(syncing=true,error=null) }
            val operations=cacheMutex.withLock { local.pending(active).take(40) }
            var conflict=false
            operations.forEach { op ->
                verify(active)
                try {
                    val receipt=withTimeout(12_000L) {
                        client.postgrest.rpc(if(op.deleting) "complete_medicine_shopping_item" else "add_medicine_shopping_item",buildJsonObject {
                            put("item_id",op.item.id)
                            if(!op.deleting) { put("item_title",op.item.title); put("item_note",op.item.note); put("local_photo",op.item.hasPhoto) }
                        }).decodeAs<ShoppingReceipt>()
                    }
                    cacheMutex.withLock {
                        verify(active); local.acknowledge(active,op,receipt)
                        publishLocal(syncing=true,error=null)
                    }
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(error: Exception) {
                    if(error.message.orEmpty().contains("SHOPPING_ID_CONFLICT")) conflict=true else throw error
                }
            }
            val changes=cacheMutex.withLock {local.changes(active).take(40)}
            var expiredRestore=false
            for(op in changes) {
                verify(active)
                val receipt=withTimeout(12_000L) {
                    client.postgrest.rpc("change_medicine_shopping_v25",buildJsonObject {
                        put("operation_id",op.operation);put("item_id",op.item.id);put("change_action",op.action)
                        put("deletion_token",op.deletionId);put("item_title",op.item.title);put("item_note",op.item.note)
                        put("local_photo",op.item.hasPhoto);put("deleted_at_ms",op.deletedAt)
                    }).decodeAs<ShoppingChangeReceipt>()
                }
                cacheMutex.withLock {verify(active);local.acknowledgeChange(active,op,receipt)}
                if(op.action=="RESTORE" && !receipt.active) expiredRestore=true
            }
            val supplierOperations=cacheMutex.withLock {suppliers.list(active).filter {it.pending}.take(40)}
            for(row in supplierOperations) {
                verify(active)
                val receipt=withTimeout(12_000L) {client.postgrest.rpc("add_supplier_ledger_entry",buildJsonObject {
                    put("entry_id",row.id);put("supplier_name",row.supplier);put("entry_kind",row.kind)
                    put("entry_amount",row.amount);put("entry_day",row.day);put("entry_note",row.note)
                }).decodeAs<ShoppingReceipt>()}
                cacheMutex.withLock {verify(active);suppliers.acknowledge(active,row,receipt)}
            }
            val after=snapshot()
            cacheMutex.withLock {
                verify(active); local.merge(active,after); suppliers.merge(active,after.suppliers);local.expire(); photos.reconcile(local)
                publishLocal(syncing=false,error=if(conflict) "تعارض في مادة معلقة؛ لم يُستبدل أي نص موجود." else if(expiredRestore) "تعذرت استعادة مادة انتهت مهلتها أو حُذفت نهائياً من جهاز أقدم." else null,synced=System.currentTimeMillis())
            }
            val pending=cacheMutex.withLock { local.pending(active).isNotEmpty() || local.changes(active).isNotEmpty() || suppliers.list(active).any {it.pending} }
            if(pending && !conflict) request()
            !pending && !conflict
        } catch(timeout: TimeoutCancellationException) {
            cacheMutex.withLock { publishLocal(syncing=false,error="الاتصال بطيء؛ القائمة محفوظة وستُزامن تلقائياً.") }; false
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) {
            cacheMutex.withLock { publishLocal(syncing=false,error="تعذر تحديث قائمة الشراء؛ بياناتها محفوظة على الهاتف وستُعاد المحاولة.") }; false
        } finally {
            if(mutable.value.syncing) cacheMutex.withLock { publishLocal(syncing=false) }
        }
    } }
    companion object {
        @Volatile private var instance: MedicineShoppingStore?=null
        fun get(context: Context): MedicineShoppingStore {
            val app=context.applicationContext
            return instance?.takeIf { it.app===app } ?: synchronized(this) {
                instance?.takeIf { it.app===app } ?: MedicineShoppingStore(app).also { instance=it }
            }
        }
    }
}

internal object ShoppingSyncScheduler {
    private const val TAG="raad-medicine-shopping-v24"
    private val network=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun ensure(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("$TAG:periodic",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ShoppingSyncWorker>(15,TimeUnit.MINUTES).setConstraints(network).addTag(TAG).build())
    }
    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("$TAG:pending",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ShoppingSyncWorker>().setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.SECONDS).addTag(TAG).build())
    }
}
class ShoppingSyncWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val store=MedicineShoppingStore.get(applicationContext)
        return try {
            if(store.sync()) Result.success()
            else if(store.identity()==null) Result.success() else Result.retry()
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { Result.retry() }
    }
}

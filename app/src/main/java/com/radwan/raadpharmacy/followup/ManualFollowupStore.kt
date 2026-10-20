package com.radwan.raadpharmacy.followup

import android.content.Context
import androidx.work.*
import com.radwan.raadpharmacy.cloud.CloudUiEvents
import com.radwan.raadpharmacy.cloud.SupabaseProvider
import com.radwan.raadpharmacy.shopping.MedicineShoppingStore
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
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

internal data class ManualFollowupState(val identity: FollowupIdentity?=null,val cases: List<ManualFollowupCase> = emptyList(),val pending: Int=0,val error: String?=null)

/** Separate queue, worker and realtime table. Financial sync and notification jobs are untouched. */
internal class ManualFollowupStore internal constructor(context: Context,
    private val clientOverride: SupabaseClient?=null,
    private val identityOverride: (() -> FollowupIdentity?)?=null,
    internal val local: ManualFollowupLocal=ManualFollowupLocal(context),
    private val enqueue: ()->Unit={FollowupSyncWorker.enqueue(context.applicationContext)}) {
    private val app=context.applicationContext
    private val client get()=clientOverride ?: SupabaseProvider.client
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val started=AtomicBoolean(false)
    private val signals=Channel<Unit>(Channel.CONFLATED)
    private val cache=Mutex()
    private val network=Mutex()
    private val mutable=MutableStateFlow(ManualFollowupState())
    @Volatile private var lastPulled=0L
    val state=mutable.asStateFlow()
    @Volatile var visible=false
    fun identity(): FollowupIdentity? = if(identityOverride!=null) identityOverride.invoke()
        else MedicineShoppingStore.get(app).identity()?.let {FollowupIdentity(it.owner,it.pharmacy)}
    private fun verify(owner: FollowupIdentity) {check(identity()==owner && client.auth.currentSessionOrNull()?.user?.id==owner.owner) {"FOLLOWUP_SESSION_CHANGED"}}
    private fun publish(error: String?=null) {
        val owner=identity()
        mutable.value=ManualFollowupState(owner,owner?.let(local::list).orEmpty(),owner?.let {local.pending(it).size} ?: 0,error)
    }
    fun start() {
        if(!started.compareAndSet(false,true)) return
        scope.launch {cache.withLock {runCatching {publish()}};for(signal in signals) sync()}
        scope.launch {
            client.auth.awaitInitialization()
            client.auth.sessionStatus.collectLatest {
                cache.withLock {runCatching {publish()}}
                val user=client.auth.currentSessionOrNull()?.user?.id ?: return@collectLatest
                supervisorScope {
                    val channel=client.channel("raad-manual-followup-$user")
                    val connected=AtomicBoolean(false)
                    val changes=channel.postgresChangeFlow<PostgresAction>("public") {table="customer_manual_followups"}
                    launch {changes.collect {request()}}
                    launch {channel.status.collect {status->
                        connected.set(status==RealtimeChannel.Status.SUBSCRIBED)
                        if(connected.get()) request()
                    }}
                    launch {runCatching {withTimeout(12_000L) {channel.subscribe(blockUntilSubscribed=true)}}}
                    request()
                    try {while(isActive) {
                        delay(if(identity()==null) 3_000L else if(visible) 30_000L else 120_000L)
                        if(CloudUiEvents.isAppForeground() && (!connected.get() || mutable.value.pending>0 || System.currentTimeMillis()-lastPulled>=600_000L)) request()
                    }}
                    finally {withContext(NonCancellable) {runCatching {withTimeout(3_000L) {channel.unsubscribe()}}}}
                }
            }
        }
    }
    fun request() {signals.trySend(Unit)}
    suspend fun add(expected: FollowupIdentity,cases: List<ManualFollowupCase>) = withContext(Dispatchers.IO) {
        cache.withLock {check(identity()==expected) {"تغيّر حساب الصيدلية. أعد فتح الشاشة."};local.add(expected,cases);publish()}
        request();runCatching {enqueue()}
    }
    suspend fun sync(): Boolean=withContext(Dispatchers.IO) {network.withLock {
        try {
            client.auth.awaitInitialization()
            val owner=identity() ?: return@withLock true
            verify(owner)
            suspend fun snapshot()=withTimeout(12_000L) {client.postgrest.rpc("customer_followup_context_v27").decodeAs<ManualFollowupSnapshot>()}
            val first=snapshot()
            cache.withLock {verify(owner);local.merge(owner,first);lastPulled=System.currentTimeMillis();publish()}
            var failed=false
            val pending=cache.withLock {local.pending(owner).take(40)}
            for(op in pending) {
                verify(owner)
                try {
                    val receipt=withTimeout(12_000L) {client.postgrest.rpc("add_customer_followup_v27",buildJsonObject {
                        put("target_customer",op.case.customer);put("debt_keys",JsonArray(op.case.debts.sorted().map(::JsonPrimitive)))
                    }).decodeAs<ManualFollowupReceipt>()}
                    cache.withLock {verify(owner);local.acknowledge(owner,op,receipt);publish()}
                } catch(cancelled: CancellationException) {throw cancelled}
                catch(_: Exception) {failed=true} // One unsynced customer must not block the others.
            }
            // Exact receipts already contain the merged keys; a second full read would only waste egress.
            cache.withLock {verify(owner);publish(if(failed) "بعض الإضافات محفوظة على الهاتف وتنتظر اكتمال مزامنة الزبون أو الدين." else null)}
            val remaining=cache.withLock {local.pending(owner).isNotEmpty()}
            if(remaining && !failed) request() // Drain large selections in bounded batches without waiting for a poll.
            !failed && !remaining
        } catch(cancelled: CancellationException) {throw cancelled}
        catch(_: Exception) {cache.withLock {runCatching {publish("الإضافات محفوظة على الهاتف؛ ستُزامن عند عودة الاتصال.")}};false}
    }}
    companion object {
        @Volatile private var instance: ManualFollowupStore?=null
        fun get(context: Context)=instance ?: synchronized(this) {instance ?: ManualFollowupStore(context.applicationContext).also {instance=it}}
    }
}

class FollowupSyncWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = if(ManualFollowupStore.get(applicationContext).sync()) Result.success() else Result.retry()
    companion object {
        fun enqueue(context: Context) {
            val work=OneTimeWorkRequestBuilder<FollowupSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("raad-manual-followup-v27",ExistingWorkPolicy.APPEND_OR_REPLACE,work)
        }
    }
}

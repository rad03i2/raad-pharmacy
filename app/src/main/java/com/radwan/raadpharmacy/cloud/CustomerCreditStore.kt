package com.radwan.raadpharmacy.cloud

import android.content.Context
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
internal data class CreditRequest(val id: String, val customerId: String, val customerName: String,
    val requestedByName: String, val block: Boolean)
@Serializable
internal data class CreditSnapshot(val owner: String = "", val pharmacy: String = "", val canManage: Boolean = false,
    val blockedIds: Set<String> = emptySet(), val requests: List<CreditRequest> = emptyList()) {
    fun preventsDebt(customerId: String) = customerId in blockedIds && !canManage
}

/** Metadata only. Never writes to the ledger, outbox, balances, login state or financial sync locks. */
internal class CustomerCreditStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("raad_customer_credit_v23", Context.MODE_PRIVATE)
    private val client get() = SupabaseProvider.client
    private val json = Json { ignoreUnknownKeys = true }
    private val revision = MutableStateFlow(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val mutex = Mutex()
    @Volatile private var decoded: Pair<String, CreditSnapshot>? = null
    private fun owner(): String? {
        val auth = SupabaseProvider.initializedClientOrNull()?.auth
        auth?.currentSessionOrNull()?.user?.id?.let { return it }
        val offline = app.getSharedPreferences("raad_cloud_auth", Context.MODE_PRIVATE).getBoolean("has_offline_session",false)
        val state = auth?.sessionStatus?.value
        return if (offline && (auth == null || state is io.github.jan.supabase.auth.status.SessionStatus.Initializing ||
                state is io.github.jan.supabase.auth.status.SessionStatus.RefreshFailure)) CloudDeviceStore(app).pushUserId() else null
    }
    fun current(): CreditSnapshot {
        val user = owner() ?: return CreditSnapshot()
        val pharmacy = CloudDeviceStore(app).pushPharmacyId() ?: return CreditSnapshot()
        val raw = prefs.getString("$user:$pharmacy", null) ?: return CreditSnapshot()
        val saved = decoded
        val snapshot = if (saved != null && saved.first == raw) saved.second else runCatching {
            json.decodeFromString<CreditSnapshot>(raw).also { decoded = raw to it }
        }.getOrNull()
        return snapshot?.takeIf { it.owner==user && it.pharmacy==pharmacy } ?: CreditSnapshot()
    }
    fun snapshots(): Flow<CreditSnapshot> = combine(revision,client.auth.sessionStatus) { _,_ -> current() }
    fun start() {
        if (!started.compareAndSet(false,true)) return
        scope.launch {
            client.auth.awaitInitialization()
            client.auth.sessionStatus.collectLatest {
                val user = client.auth.currentSessionOrNull()?.user?.id ?: return@collectLatest
                supervisorScope {
                    val channel = client.channel("raad-customer-credit-$user")
                    val changes = channel.postgresChangeFlow<PostgresAction>("public") { table="customer_credit_controls" }
                    val requests = channel.postgresChangeFlow<PostgresAction>("public") { table="customer_credit_requests" }
                    launch { changes.collect { refreshQuietly() } }
                    launch { requests.collect { refreshQuietly() } }
                    launch { runCatching { withTimeout(12_000L) { channel.subscribe(blockUntilSubscribed=true) } } }
                    try {
                        while (isActive && client.auth.currentSessionOrNull()?.user?.id==user) {
                            refreshQuietly()
                            delay(if (CloudUiEvents.isAppForeground()) 15_000L else 60_000L)
                        }
                    } finally { withContext(NonCancellable) { runCatching { withTimeout(3_000L) { channel.unsubscribe() } } } }
                }
            }
        }
    }
    fun requestRefresh() { scope.launch { refreshQuietly() } }
    private suspend fun refreshQuietly() {
        try { refresh() } catch (_: TimeoutCancellationException) { /* A request deadline does not stop future polls. */ }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Cached block state remains; next foreground/realtime/poll retries independently. */ }
    }
    suspend fun refresh(): CreditSnapshot = mutex.withLock {
        client.auth.awaitInitialization()
        val user = checkNotNull(client.auth.currentSessionOrNull()?.user?.id) { "AUTH_SESSION_REQUIRED" }
        val result = withTimeout(10_000L) { client.postgrest.rpc("customer_credit_context").decodeAs<CreditSnapshot>() }
        check(result.owner==user && client.auth.currentSessionOrNull()?.user?.id==user) { "AUTH_SESSION_CHANGED" }
        val pharmacy = CloudDeviceStore(app).pushPharmacyId()
        check(pharmacy==null || result.pharmacy==pharmacy) { "AUTH_SESSION_CHANGED" }
        check(prefs.edit().putString("${result.owner}:${result.pharmacy}",json.encodeToString(result)).commit())
        revision.value++
        result
    }
    suspend fun setBlocked(customer: String, block: Boolean, requestId: String? = null) = mutex.withLock {
        val user = checkNotNull(client.auth.currentSessionOrNull()?.user?.id) { "AUTH_SESSION_REQUIRED" }
        withTimeout(15_000L) {
            client.postgrest.rpc(if (requestId==null) "set_customer_credit" else "resolve_credit_request",buildJsonObject {
                if (requestId==null) put("target_customer",customer) else put("request_id",requestId)
                put("make_blocked",block)
            })
        }
        check(client.auth.currentSessionOrNull()?.user?.id==user) { "AUTH_SESSION_CHANGED" }
        // A lost refresh must not undo a server-confirmed change in this account's local metadata.
        val old = current()
        if (old.owner==user) {
            val updated = old.copy(blockedIds=if(block) old.blockedIds+customer else old.blockedIds-customer,
                requests=old.requests.filterNot { it.id==requestId })
            check(prefs.edit().putString("${old.owner}:${old.pharmacy}",json.encodeToString(updated)).commit()); revision.value++
        }
        requestRefresh()
    }
    suspend fun request(customer: String, block: Boolean) {
        val user = checkNotNull(client.auth.currentSessionOrNull()?.user?.id) { "AUTH_SESSION_REQUIRED" }
        val key="pending:$user:$customer:$block"
        val id = synchronized(prefs) { prefs.getString(key,null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString(key,it).commit())
        } }
        withTimeout(15_000L) {
            val device=CloudDeviceRegistration.ensure(app,user)
            val receipt = client.postgrest.rpc("request_customer_credit",buildJsonObject {
                put("target_customer",customer); put("make_blocked",block); put("request_id",id); put("sender_device_id",device)
            }).decodeAs<String>()
            check(receipt == id) { "CREDIT_REQUEST_RECEIPT_MISMATCH" }
        }
        check(client.auth.currentSessionOrNull()?.user?.id==user) { "AUTH_SESSION_CHANGED" }
        check(prefs.edit().remove(key).commit())
        requestRefresh()
    }
    companion object {
        @Volatile private var instance: CustomerCreditStore?=null
        fun get(context: Context): CustomerCreditStore {
            val app = context.applicationContext
            return instance?.takeIf { it.app === app } ?: synchronized(this) {
                instance?.takeIf { it.app === app } ?: CustomerCreditStore(app).also { instance=it }
            }
        }
        const val BLOCKED_MESSAGE="هذا الزبون محجوب من رعد؛ التحصيل متاح، وإضافة دين تتطلب رفع الحجب من رعد."
    }
}

package com.radwan.raadpharmacy.followup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.cloud.createCloudClient
import com.sun.net.httpserver.HttpServer
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.*
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(SupabaseInternal::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35])
class ManualFollowupHttpTest {
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private val owner=FollowupIdentity("00000000-0000-4000-8000-000000000011","00000000-0000-4000-8000-000000000001")
    private lateinit var server: HttpServer
    private val executor=Executors.newCachedThreadPool {r->Thread(r,"followup-fixture").apply {isDaemon=true}}
    private val rows=ConcurrentHashMap<String,Set<String>>()
    private val paths=CopyOnWriteArrayList<String>()
    private val outage=AtomicBoolean(false)
    private val wrong=AtomicBoolean(false)
    private val lost=AtomicBoolean(false)
    private val clients=mutableListOf<SupabaseClient>()
    private val stores=mutableListOf<ManualFollowupStore>()
    private val databases=mutableListOf<String>()
    @Before fun open() {
        server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0);server.executor=executor
        server.createContext("/") {e->
            val path=e.requestURI.path;paths+=path
            var status=200
            val body=when(path) {
                "/auth/v1/token"->JSONObject().put("access_token","fixture-token").put("refresh_token","fixture-refresh").put("token_type","bearer").put("expires_in",3600)
                    .put("user",JSONObject().put("id",owner.owner).put("aud","authenticated").put("role","authenticated").put("created_at","2026-10-20T00:00:00Z")).toString()
                "/rest/v1/rpc/customer_followup_context_v27"->if(outage.get()) {status=503;"{}"} else Json.encodeToString(ManualFollowupSnapshot(owner.owner,owner.pharmacy,rows.map {ManualFollowupCase(it.key,it.value)}))
                "/rest/v1/rpc/add_customer_followup_v27"->{
                    val request=JSONObject(e.requestBody.bufferedReader().use {it.readText()})
                    val id=request.getString("target_customer");val keys=request.getJSONArray("debt_keys");val debts=(0 until keys.length()).map {keys.getString(it)}.toSet()
                    rows.compute(id) {_,old->old.orEmpty()+debts}
                    if(lost.compareAndSet(true,false)) {e.close();return@createContext}
                    Json.encodeToString(ManualFollowupReceipt(if(wrong.get()) "wrong-owner" else owner.owner,owner.pharmacy,id,rows[id].orEmpty()))
                }
                else->{status=404;"{}"}
            }
            val bytes=body.toByteArray();e.responseHeaders.add("Content-Type","application/json");e.sendResponseHeaders(status,bytes.size.toLong());e.responseBody.use {it.write(bytes)}
        };server.start()
    }
    private suspend fun device(): ManualFollowupStore {
        val client=createCloudClient("http://127.0.0.1:${server.address.port}","fixture-key") {
            autoLoadFromStorage=false;autoSaveToStorage=false;alwaysAutoRefresh=false;autoSetupPlatform=false;sessionManager=MemorySessionManager();codeVerifierCache=MemoryCodeVerifierCache()
        };clients+=client
        client.auth.signInWith(Email) {email="fixture@example.invalid";password="fixture-only"}
        val name="followup-http-${UUID.randomUUID()}.db";databases+=name
        val store=ManualFollowupStore(context,client,{owner},ManualFollowupLocal(context,name),enqueue={});stores+=store
        assertTrue(store.sync());return store
    }
    @After fun close()=runBlocking {stores.forEach {it.local.close()};clients.forEach {it.close()};server.stop(0);executor.shutdownNow();databases.forEach {context.deleteDatabase(it)};Unit}
    @Test fun offlineAndLostResponseRetainExactPendingCaseAndRetryMergesAcrossTwoDevicesWithoutFinancialCalls()=runBlocking {
        val first=device();val second=device()
        first.add(owner,listOf(ManualFollowupCase("customer",setOf("entry:debt"))))
        outage.set(true);assertFalse(first.sync());assertEquals(1,first.state.value.pending)
        outage.set(false);lost.set(true);assertFalse(first.sync());assertEquals(1,first.state.value.pending)
        assertTrue(first.sync());assertTrue(second.sync());assertEquals(1,rows.size)
        assertEquals(setOf("entry:debt"),second.state.value.cases.single().debts);assertEquals(0,first.state.value.pending)
        assertFalse(paths.any {"transactions" in it || "notification" in it || "storage" in it || it.contains("/customers")})
    }
    @Test fun aWrongReceiptCannotAcknowledgeWorkEvenWhenAFollowingSnapshotContainsItsRows()=runBlocking {
        val store=device();store.add(owner,listOf(ManualFollowupCase("customer",setOf("entry:debt"))))
        wrong.set(true);assertFalse(store.sync());assertEquals(1,store.state.value.pending)
        assertFalse(store.sync());assertEquals(1,store.state.value.pending) // A later snapshot alone still cannot ACK it.
        wrong.set(false);assertTrue(store.sync());assertEquals(0,store.state.value.pending)
    }
}

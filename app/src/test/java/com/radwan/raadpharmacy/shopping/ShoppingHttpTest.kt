package com.radwan.raadpharmacy.shopping

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.cloud.createCloudClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.*
import io.github.jan.supabase.auth.providers.builtin.Email
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(SupabaseInternal::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35])
class ShoppingHttpTest {
    private val owner="00000000-0000-4000-8000-000000000011"
    private val pharmacy="00000000-0000-4000-8000-000000000001"
    private val item="00000000-0000-4000-8000-000000000071"
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private lateinit var server: HttpServer
    private val executor=Executors.newCachedThreadPool { r -> Thread(r,"shopping-fixture").apply {isDaemon=true} }
    private val rows=ConcurrentHashMap<String,ShoppingItem>()
    private val trashRows=ConcurrentHashMap<String,ShoppingTrash>()
    private val supplierRows=ConcurrentHashMap<String,com.radwan.raadpharmacy.suppliers.SupplierEntry>()
    private val mutations=ConcurrentHashMap<String,ShoppingChangeReceipt>()
    private val deleted=ConcurrentHashMap.newKeySet<String>()
    private val posts=CopyOnWriteArrayList<JSONObject>()
    private val paths=CopyOnWriteArrayList<String>()
    private val outage=AtomicBoolean(false)
    private val loseReply=AtomicBoolean(false)
    private val wrongReceipt=AtomicBoolean(false)
    private val clients=mutableListOf<SupabaseClient>()
    private val stores=mutableListOf<MedicineShoppingStore>()
    private val roots=mutableListOf<File>()
    @Before fun open() {
        server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0);server.executor=executor
        server.createContext("/") { e ->
            paths+=e.requestURI.path
            when(e.requestURI.path) {
                "/auth/v1/token" -> reply(e,200,JSONObject().put("access_token","fixture-token").put("refresh_token","fixture-refresh")
                    .put("token_type","bearer").put("expires_in",3600).put("user",JSONObject().put("id",owner)
                        .put("aud","authenticated").put("role","authenticated").put("created_at","2026-10-18T00:00:00Z")).toString())
                "/rest/v1/rpc/medicine_shopping_context", "/rest/v1/rpc/medicine_shopping_context_v25" -> if(outage.get()) reply(e,503,"""{"code":"PGRST000","message":"fixture offline"}""")
                    else reply(e,200,kotlinx.serialization.json.Json.encodeToString(ShoppingSnapshot(owner,pharmacy,rows.values.toList(),trashRows.values.toList(),supplierRows.values.toList())))
                "/rest/v1/rpc/add_medicine_shopping_item", "/rest/v1/rpc/complete_medicine_shopping_item" -> {
                    val data=JSONObject(e.requestBody.bufferedReader().use {it.readText()});posts+=data
                    val id=data.getString("item_id")
                    val completing=e.requestURI.path.endsWith("complete_medicine_shopping_item")
                    if(completing) {deleted+=id;rows.remove(id)}
                    else if(id !in deleted) rows.putIfAbsent(id,ShoppingItem(id,data.getString("item_title"),data.getString("item_note"),
                        data.getBoolean("local_photo"),System.currentTimeMillis(),"رعد"))
                    if(loseReply.compareAndSet(true,false)) e.close()
                    else reply(e,200,kotlinx.serialization.json.Json.encodeToString(ShoppingReceipt(owner,pharmacy,
                        if(wrongReceipt.get()) UUID.randomUUID().toString() else id,id !in deleted)))
                }
                "/rest/v1/rpc/change_medicine_shopping_v25" -> {
                    val data=JSONObject(e.requestBody.bufferedReader().use {it.readText()});posts+=data
                    val id=data.getString("item_id");val op=data.getString("operation_id")
                    val receipt=mutations.computeIfAbsent(op) {
                        if(data.getString("change_action")=="TRASH") {
                            if(id !in deleted) {
                                val row=rows.remove(id)?:ShoppingItem(id,data.getString("item_title"),data.getString("item_note"),data.getBoolean("local_photo"))
                                deleted+=id;trashRows[id]=ShoppingTrash(row,op,data.getLong("deleted_at_ms")+86_400_000)
                            }
                        } else {
                            val held=trashRows[id]
                            if(held!=null && held.deletionId==data.getString("deletion_token") && held.expiresAt>System.currentTimeMillis()) {
                                rows[id]=held.item;trashRows.remove(id);deleted.remove(id)
                            }
                        }
                        ShoppingChangeReceipt(owner,pharmacy,id,op,id in rows,id in trashRows)
                    }
                    if(loseReply.compareAndSet(true,false)) e.close()
                    else reply(e,200,kotlinx.serialization.json.Json.encodeToString(receipt.copy(operation=if(wrongReceipt.get()) UUID.randomUUID().toString() else op)))
                }
                "/rest/v1/rpc/add_supplier_ledger_entry" -> {
                    val data=JSONObject(e.requestBody.bufferedReader().use {it.readText()});posts+=data
                    val id=data.getString("entry_id")
                    supplierRows.putIfAbsent(id,com.radwan.raadpharmacy.suppliers.SupplierEntry(id,data.getString("supplier_name"),data.getString("entry_kind"),data.getLong("entry_amount"),data.getString("entry_day"),data.getString("entry_note")))
                    if(loseReply.compareAndSet(true,false)) e.close()
                    else reply(e,200,kotlinx.serialization.json.Json.encodeToString(ShoppingReceipt(owner,pharmacy,id,true)))
                }
                else -> reply(e,404,"{}")
            }
        };server.start()
    }
    private fun reply(e: HttpExchange,status: Int,body: String) {
        e.responseHeaders.add("Content-Type","application/json");val bytes=body.toByteArray()
        e.sendResponseHeaders(status,bytes.size.toLong());e.responseBody.use {it.write(bytes)}
    }
    private suspend fun device(): MedicineShoppingStore {
        val client=createCloudClient("http://127.0.0.1:${server.address.port}","fixture-key") {
            autoLoadFromStorage=false;autoSaveToStorage=false;alwaysAutoRefresh=false;autoSetupPlatform=false
            sessionManager=MemorySessionManager();codeVerifierCache=MemoryCodeVerifierCache()
        };clients+=client
        client.auth.signInWith(Email) {email="fixture@example.invalid";password="fixture-only"}
        val id=UUID.randomUUID().toString();val root=File(context.noBackupFilesDir,"shopping-http-$id");roots+=root
        val store=MedicineShoppingStore(context,client,root,"shopping-test-$id",enqueueWork={});stores+=store
        assertTrue(store.sync());return store
    }
    @After fun close()=runBlocking { stores.forEach {it.local.close();it.suppliers.close()};clients.forEach {it.close()};server.stop(0);executor.shutdownNow()
        roots.forEach {it.deleteRecursively()};Unit }
    @Test fun seventyMaterialsExchangeAcrossTwoDevicesAndUnboughtTenRemainWithNewAdditions()=runBlocking {
        val first=device();val second=device()
        repeat(70) {first.add(UUID.randomUUID().toString(),"علاج $it","علبتان",false)}
        repeat(2) {first.sync()};assertEquals(70,rows.size);assertTrue(second.sync());assertEquals(70,second.state.value.items.size)
        second.state.value.items.take(60).forEach {second.complete(it.id)}
        repeat(2) {second.sync()};assertEquals(10,rows.size)
        assertTrue(first.sync());assertEquals(10,first.state.value.items.size)
        first.add(UUID.randomUUID().toString(),"إضافة الجولة الجديدة","",false);assertTrue(first.sync());assertTrue(second.sync())
        assertEquals(11,second.state.value.items.size)
        assertTrue(paths.all {it.startsWith("/rest/v1/rpc/")||it=="/auth/v1/token"})
        assertFalse(paths.any {"storage" in it || "transactions" in it || "notification" in it})
    }
    @Test fun offlineAndLostReplyPreserveOriginalUuidUntilServerConfirmsIt()=runBlocking {
        val first=device();first.add(item,"علاج تجريبي","عبوة",false)
        outage.set(true);assertFalse(first.sync());assertEquals(1,first.state.value.pending);assertEquals(1,first.state.value.items.size)
        outage.set(false);loseReply.set(true);assertFalse(first.sync())
        assertEquals(1,rows.size);assertEquals(1,first.local.pending(first.identity()!!).size)
        assertTrue(first.sync());assertEquals(1,rows.size);assertEquals(0,first.state.value.pending)
        assertTrue(posts.all {it.getString("item_id")==item})
    }
    @Test fun purchaseOnOneDeviceRejectsAnOlderOfflineCreateOnAnotherWithoutResurrection()=runBlocking {
        val first=device();val other=device()
        first.add(item,"علاج","",false);other.add(item,"علاج","",false)
        assertTrue(first.sync());first.complete(item);assertTrue(first.sync())
        assertTrue(other.sync());assertTrue(rows.isEmpty());assertTrue(other.state.value.items.isEmpty());assertEquals(0,other.state.value.pending)
        assertTrue(deleted.contains(item))
    }
    @Test fun photoBytesStayOnlyOnTheirOriginalDeviceAndAreRemovedWhenAnotherDeviceBuysTheItem()=runBlocking {
        val first=device();val other=device()
        first.photos.draft(item).apply {parentFile!!.mkdirs();writeText("PRIVATE_LOCAL_IMAGE_BYTES")}
        first.add(item,"","علبتان",true);assertTrue(first.sync());assertTrue(other.sync())
        assertTrue(first.photos.file(pharmacy,item).isFile)
        assertFalse(other.photos.file(pharmacy,item).exists())
        assertTrue(other.state.value.items.single().hasPhoto)
        assertTrue(posts.all { data -> data.keys().asSequence().toSet()==setOf("item_id","item_title","item_note","local_photo") })
        assertFalse(posts.any {"PRIVATE_LOCAL_IMAGE_BYTES" in it.toString() || "no_backup" in it.toString()})
        other.complete(item);assertTrue(other.sync());assertTrue(first.sync())
        assertFalse(first.photos.file(pharmacy,item).exists());assertTrue(first.state.value.items.isEmpty())
    }
    @Test fun recoverablePurchaseAndRestoreCrossDevicesRetainOnlyOriginalLocalPhoto()=runBlocking {
        val first=device();val second=device()
        first.photos.draft(item).apply {parentFile!!.mkdirs();writeText("PRIVATE_LOCAL_IMAGE_BYTES")}
        first.add(item,"علاج","3 علب",true);assertTrue(first.sync());assertTrue(second.sync())
        second.purchase(item);loseReply.set(true);assertFalse(second.sync())
        assertEquals(1,second.local.changes(second.identity()!!).size)
        assertTrue(second.sync());assertTrue(first.sync())
        assertTrue(first.state.value.items.isEmpty());assertEquals(item,first.state.value.trash.single().item.id)
        assertTrue(first.photos.file(pharmacy,item).exists());assertFalse(second.photos.file(pharmacy,item).exists())
        first.restore(item);assertTrue(first.sync());assertTrue(second.sync())
        assertEquals(item,second.state.value.items.single().id);assertTrue(first.state.value.trash.isEmpty())
        assertEquals(1,rows.size);assertTrue(first.photos.file(pharmacy,item).exists())
        assertFalse(posts.any {"PRIVATE_LOCAL_IMAGE_BYTES" in it.toString()})
    }
    @Test fun offlineSupplierDebtAndPaymentUseExactIdsAndConfirmedServerReceipts()=runBlocking {
        val first=device();val second=device()
        val row=com.radwan.raadpharmacy.suppliers.SupplierEntry(item,"مذخر اختبار","DEBT",9_000_000,"2026-10-18")
        first.addSupplier(row);outage.set(true);assertFalse(first.sync());assertTrue(first.state.value.suppliers.single().pending)
        outage.set(false);loseReply.set(true);assertFalse(first.sync());assertTrue(first.state.value.suppliers.single().pending)
        assertTrue(first.sync());assertTrue(second.sync())
        assertEquals(9_000_000L,second.state.value.suppliers.single().amount);assertEquals(1,supplierRows.size)
        assertFalse(first.state.value.suppliers.single().pending)
    }
    @Test fun wrongReceiptsNeverClearPendingAdditionOrDeletion()=runBlocking {
        val first=device();first.add(item,"علاج","",false);wrongReceipt.set(true)
        assertFalse(first.sync());assertEquals(1,first.state.value.pending)
        wrongReceipt.set(false);assertTrue(first.sync());first.complete(item)
        wrongReceipt.set(true);assertFalse(first.sync());assertEquals(1,first.state.value.pending);assertTrue(first.state.value.items.isEmpty())
        wrongReceipt.set(false);assertTrue(first.sync());assertEquals(0,first.state.value.pending)
    }
}

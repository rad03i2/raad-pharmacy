package com.radwan.raadpharmacy.cloud

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.backup.*
import com.radwan.raadpharmacy.data.*
import com.radwan.raadpharmacy.BuildConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.statement.bodyAsText
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Real sockets and the production Supabase/CIO client, not a mocked upload function.
 * The only server is an isolated localhost fixture; no production account or money is touched. */
@OptIn(SupabaseInternal::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudHttpIntegrationTest {
    private lateinit var server: HttpServer
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "fixture-http").apply { isDaemon = true } }
    private val clients = mutableListOf<SupabaseClient>()
    private val rows = ConcurrentHashMap<String, String>()
    private val customerRows = ConcurrentHashMap<String, String>()
    private val writePreferences = CopyOnWriteArrayList<String>()
    private val postPaths = CopyOnWriteArrayList<String>()
    private val agents = CopyOnWriteArrayList<String>()
    private val paths = CopyOnWriteArrayList<String>()
    private val patches = CopyOnWriteArrayList<JSONObject>()
    private val rejectWrites = AtomicBoolean(false)
    private val wrongAcknowledgement = AtomicBoolean(false)
    private val canonicalOffsetReply = AtomicBoolean(false)
    private val loseResponse = AtomicBoolean(false)
    private val loseInsertResponse = AtomicBoolean(false)
    private val rejectLogin = AtomicBoolean(false)
    private val rejectDevice = AtomicBoolean(false)
    private val owner = "00000000-0000-4000-8000-000000000001"
    private val pharmacy = "00000000-0000-4000-8000-000000000002"
    private val customer = "00000000-0000-4000-8000-000000000003"
    private val entry = "00000000-0000-4000-8000-000000000004"
    private val instant = "2026-10-17T00:00:00Z"
    private val url get() = "http://127.0.0.1:${server.address.port}"

    @Before fun start() {
        CloudDeviceRegistration.invalidate()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            agents += exchange.requestHeaders.getFirst("User-Agent").orEmpty()
            paths += exchange.requestURI.path
            when (exchange.requestURI.path) {
                "/slow" -> { Thread.sleep(350); runCatching { reply(exchange, 200, "{}") } }
                "/auth/v1/token" -> if (rejectLogin.get()) reply(exchange, 400,
                    """{"code":"invalid_credentials","msg":"Invalid login credentials"}""")
                else reply(exchange, 200, JSONObject().put("access_token", "fixture-access-token")
                    .put("refresh_token", "fixture-refresh-token").put("token_type", "bearer").put("expires_in", 3600)
                    .put("user", JSONObject().put("id", owner).put("aud", "authenticated").put("role", "authenticated")
                        .put("email", "fixture@example.invalid").put("created_at", instant)).toString())
                "/rest/v1/profiles" -> reply(exchange, 200, """[{"id":"$owner","pharmacy_id":"$pharmacy","display_name":"fixture","role":"MANAGER"}]""")
                "/rest/v1/devices" -> if (rejectDevice.get()) reply(exchange, 503,
                    """{"code":"PGRST000","message":"fixture unavailable"}""") else reply(exchange, 201, "")
                "/rest/v1/rpc/send_team_message" -> {
                    val data = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                    reply(exchange, 200, JSONObject.quote(data.getString("message_id")))
                }
                "/rest/v1/transactions", "/rest/v1/customers" -> {
                    val table = if (exchange.requestURI.path.endsWith("customers")) customerRows else rows
                    val filters = exchange.requestURI.rawQuery.orEmpty().split('&').mapNotNull {
                        val pair = it.split('=', limit = 2)
                        if (pair.size == 2) URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") else null
                    }.toMap()
                    fun matches(row: JSONObject) = filters.all { (key, value) -> when {
                        value.startsWith("eq.") -> row.optString(key) == value.removePrefix("eq.")
                        value == "is.null" -> row.isNull(key)
                        else -> true
                    } }
                    if (exchange.requestMethod == "GET") reply(exchange, 200,
                        JSONArray(table.values.map(::JSONObject).filter(::matches)).toString())
                    else if (rejectWrites.get()) reply(exchange, 503, """{"code":"PGRST000","message":"fixture offline"}""")
                    else {
                    val preference = exchange.requestHeaders.getFirst("Prefer").orEmpty()
                    writePreferences += preference
                    val body = exchange.requestBody.bufferedReader().use { it.readText() }
                    val data = if (body.trimStart().startsWith("[")) JSONArray(body).getJSONObject(0) else JSONObject(body)
                    val immutable = if (table === customerRows) listOf("id", "pharmacy_id", "created_at")
                        else listOf("id", "pharmacy_id", "customer_id", "operation_id", "created_at", "created_by", "created_device_id")
                    if (exchange.requestMethod == "PATCH") {
                        patches += JSONObject(data.toString())
                        val changed = table.values.map(::JSONObject).filter(::matches)
                        if (changed.any { old -> immutable.any { data.has(it) && data.opt(it) != old.opt(it) } })
                            reply(exchange, 400, """{"code":"P0001","message":"immutable identity fields cannot be changed"}""")
                        else {
                            changed.forEach { old -> data.keys().forEach { old.put(it, data.get(it)) }; table[old.getString("id")] = old.toString() }
                            if (canonicalOffsetReply.get()) changed.forEach { old ->
                                old.put("occurred_at", "2026-10-17T00:00:00.000000+00:00")
                                table[old.getString("id")] = old.toString()
                            }
                            if (wrongAcknowledgement.get()) changed.forEach { it.put("amount", -1).put("name", "wrong acknowledgement") }
                            if (loseResponse.compareAndSet(true, false)) exchange.close()
                            else reply(exchange, 200, JSONArray(changed).toString())
                        }
                    } else {
                    postPaths += exchange.requestURI.path
                    val previous = table[data.getString("id")]?.let(::JSONObject)
                    if (previous != null && "merge-duplicates" !in preference)
                        reply(exchange, 409, """{"code":"23505","message":"fixture duplicate key"}""")
                    else if (previous != null && immutable.any { data.has(it) && data.opt(it) != previous.opt(it) })
                        reply(exchange, 400, """{"code":"P0001","message":"immutable customer identity fields cannot be changed"}""")
                    else {
                    data.put("updated_at", instant); table[data.getString("id")] = data.toString()
                    if (loseResponse.compareAndSet(true, false) || loseInsertResponse.compareAndSet(true, false)) exchange.close()
                    else reply(exchange, 201, if ("return=representation" in preference) JSONArray().put(data).toString() else "")
                    }
                    }
                    }
                }
                else -> reply(exchange, 200, "{}")
            }
        }
        server.start()
    }
    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
    }
    private fun client(manager: MemorySessionManager = MemorySessionManager(), restore: Boolean = false) =
        createCloudClient(url, "fixture-publishable-key") {
            autoLoadFromStorage = restore; autoSaveToStorage = true; alwaysAutoRefresh = false
            autoSetupPlatform = false; sessionManager = manager; codeVerifierCache = MemoryCodeVerifierCache()
        }.also { clients += it }
    private suspend fun login(client: SupabaseClient) {
        client.auth.signInWith(Email) { email = "fixture@example.invalid"; password = "fixture-password" }
    }
    private fun debt(amount: Long = 7000) = CloudTransactionWrite(entry, pharmacy, customer, entry,
        "DEBT", amount, occurredAt = instant, createdAt = instant)
    @After fun close() = runBlocking {
        CloudDeviceRegistration.invalidate()
        clients.forEach { it.close() }; server.stop(0); executor.shutdownNow(); Unit
    }

    @Test fun twoProductionClientsExchangeTheSameFinancialOperationOverRealHttp() = runBlocking {
        val first = client(); val second = client(); login(first); login(second)
        first.from("transactions").upsert(debt()) { onConflict = "id" }
        val received = second.from("transactions").select().decodeList<CloudTransactionRow>().single()
        assertEquals(entry, received.operationId); assertEquals(7000.0, received.amount, 0.0)
        second.from("transactions").upsert(debt(9000)) { onConflict = "id" }
        assertEquals(9000.0, first.from("transactions").select().decodeList<CloudTransactionRow>().single().amount, 0.0)
        assertTrue(agents.all { it == "RaadPharmacy/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" })
    }

    @Test fun aTimedOutSocketRequestDoesNotPreventLoginOrTheNextFinancialRequest() = runBlocking {
        val client = client()
        assertFalse(recoverableCloudOperation { withTimeout(60) { client.httpClient.request("$url/slow") { }.bodyAsText() } })
        login(client)
        client.from("transactions").upsert(debt()) { onConflict = "id" }
        assertEquals(owner, client.auth.currentSessionOrNull()!!.user!!.id)
        assertEquals(1, rows.size)
    }

    @Test fun aLostResponseRetriesTheOriginalUuidWithoutDuplicatingTheAmount() = runBlocking {
        val client = client(); login(client); loseResponse.set(true)
        runCatching { client.from("transactions").upsert(debt()) { onConflict = "id" } }
        client.from("transactions").upsert(debt()) { onConflict = "id" }
        assertEquals(1, rows.size)
        assertEquals(7000L, JSONObject(rows.values.single()).getLong("amount"))
    }

    @Test fun aStoredSessionRestoresWithoutTheApplicationReimportingIt() = runBlocking {
        val manager = MemorySessionManager(); val first = client(manager); login(first)
        val restored = client(manager, restore = true); restored.auth.awaitInitialization()
        val profile = restored.from("profiles").select().decodeList<CloudProfileRow>().single()
        assertEquals(owner, profile.id); assertEquals(owner, restored.auth.currentSessionOrNull()!!.user!!.id)
    }

    @Test fun theActualAuthErrorIsReportedAsCredentialsRatherThanAnInternetFailure() = runBlocking {
        val client = client(); rejectLogin.set(true)
        val error = runCatching { login(client) }.exceptionOrNull()!!
        assertTrue(CloudLoginFeedback.forFailure(error).contains("بيانات الدخول"))
        assertEquals(CloudFailureCode.HTTP, CloudConnectionDiagnostics.classify(error).code)
    }

    @Test fun aConfirmedLedgerRegistrationLetsEachWarmMessageUseOnlyOneHttpRequest() = runBlocking {
        val client = client(); login(client)
        val app = ApplicationProvider.getApplicationContext<Context>()
        val profile = client.from("profiles").select().decodeList<CloudProfileRow>().single()
        val device = CloudDeviceRegistration.register(app, profile, owner, client)
        paths.clear()
        repeat(2) {
            val ready = CloudDeviceRegistration.ensure(app, owner, client = client)
            assertEquals(device, ready)
            val id = java.util.UUID.randomUUID().toString()
            assertEquals(id, client.postgrest.rpc("send_team_message", buildJsonObject {
                put("target_user_id", customer); put("sender_device_id", ready)
                put("message_id", id); put("message_text", "fixture")
            }).decodeAs<String>())
        }
        assertEquals(listOf("/rest/v1/rpc/send_team_message", "/rest/v1/rpc/send_team_message"), paths.toList())
    }

    @Test fun aFailedDeviceRegistrationIsNeverAcceptedAsConfirmed() = runBlocking {
        val client = client(); login(client)
        val app = ApplicationProvider.getApplicationContext<Context>()
        rejectDevice.set(true)
        assertTrue(runCatching { CloudDeviceRegistration.ensure(app, owner, client = client) }.isFailure)
        rejectDevice.set(false); paths.clear()
        val device = CloudDeviceRegistration.ensure(app, owner, client = client)
        assertEquals(CloudDeviceStore(app).deviceId(), device)
        assertEquals(listOf("/rest/v1/profiles", "/rest/v1/devices"), paths.toList())
    }

    @Test fun registrationIsScopedToTheActiveOwnerAndRevalidatedAfterInvalidationOrExpiry() = runBlocking {
        val client = client(); login(client)
        val app = ApplicationProvider.getApplicationContext<Context>()
        val device = CloudDeviceRegistration.ensure(app, owner, client = client)
        paths.clear()
        assertTrue(runCatching { CloudDeviceRegistration.ensure(app, customer, client = client) }.isFailure)
        assertTrue(paths.isEmpty())
        CloudDeviceRegistration.invalidate()
        assertEquals(device, CloudDeviceRegistration.ensure(app, owner, client = client))
        assertEquals(listOf("/rest/v1/profiles", "/rest/v1/devices"), paths.toList())
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61)); paths.clear()
        assertEquals(device, CloudDeviceRegistration.ensure(app, owner, client = client))
        assertEquals(listOf("/rest/v1/profiles", "/rest/v1/devices"), paths.toList())
    }

    @Test fun recoveryUsesRealInsertRequestsAndConfirmsTheSavedRowsByReadingThemBack() = runBlocking {
        val client = client(); login(client)
        val time = java.time.Instant.parse(instant).toEpochMilli()
        val c = CustomerEntity(customer, "fixture", null, "", "", 0, "", time)
        val e = LedgerEntryEntity(entry, customer, "DEBT", 9000, null, null, "", time + 1000)
        val remote = SupabaseRecoveryRemote(client, pharmacy, "00000000-0000-4000-8000-000000000009", owner)
        remote.insert(c); remote.insert(e)
        assertEquals(customer, remote.customer(customer)!!.id)
        assertEquals(9000.0, remote.entry(entry)!!.amount, 0.0)
        assertTrue(writePreferences.none { "merge-duplicates" in it })
        assertEquals(listOf("/rest/v1/customers", "/rest/v1/transactions"), postPaths.toList())
    }

    @Test fun actualRecoveryClientReconcilesALostCommitReplyWithoutPostingTheAmountAgain() = runBlocking {
        val client = client(); login(client)
        val time = java.time.Instant.parse(instant).toEpochMilli()
        val c = CustomerEntity(customer, "fixture", null, "", "", 0, "", time)
        val e = LedgerEntryEntity(entry, customer, "DEBT", 9000, null, null, "", time + 1000)
        val remote = SupabaseRecoveryRemote(client, pharmacy, "00000000-0000-4000-8000-000000000009", owner)
        remote.insert(c); postPaths.clear(); writePreferences.clear()
        val db = Room.inMemoryDatabaseBuilder<PharmacyLedgerDatabase>(ApplicationProvider.getApplicationContext<Context>())
            .setDriver(AndroidSQLiteDriver()).build()
        try {
            db.dao().insertCustomer(c, "REMOTE")
            val input = RestoredArchive(BackupPayload(listOf(c.toModel()), listOf(e.toModel()), time + 2000, 1), emptyList(), JSONObject(), 0)
            val importer = RecoveryImporter(db.dao()) {}
            importer.confirm(input, importer.compare(input).sequence, owner, pharmacy)
            loseResponse.set(true)
            runCatching { RecoveryCloudVerifier(db.dao(), remote).run(owner, pharmacy) }
            val count = postPaths.size
            RecoveryCloudVerifier(db.dao(), remote).run(owner, pharmacy)
            assertEquals(count, postPaths.size)
            assertEquals(1, rows.size)
            assertTrue(db.dao().recoveryEntries().isEmpty())
            assertTrue(writePreferences.none { "merge-duplicates" in it })
        } finally { db.close() }
    }

    @Test fun areaOnlyPatchPreservesNewerServerMoneyNamePhoneAndCreationMetadata()=runBlocking {
        val client=client();login(client)
        val current=customerWrite().copy(name="newer server name",openingDebt=9_000_000,phone="07800000000",notes="newer server notes",area="previous")
        seed(current)
        val before=JSONObject(customerRows.getValue(customer))
        val writer=CloudLedgerWriter(client,pharmacy)
        writer.saveArea(customer,"الكرادة")
        writer.saveArea(customer,"الكرادة") // Retry is the same PATCH, never an INSERT.
        val after=JSONObject(customerRows.getValue(customer))
        before.keys().forEach {if(it!="area") assertEquals(it,before.get(it),after.get(it))}
        assertEquals("الكرادة",after.getString("area"));assertTrue(postPaths.isEmpty())
        assertTrue(patches.all {it.keys().asSequence().toSet()==setOf("area")})
        customerRows.clear()
        var rejected=false
        try {writer.saveArea(customer,"المفقود")} catch(_:CloudAreaWriteConflict) {rejected=true}
        assertTrue(rejected);assertTrue(customerRows.isEmpty());assertTrue(postPaths.isEmpty())
    }

    private val preciseCreation = "2026-10-17T00:00:00.000123Z"
    private fun customerWrite() = CloudCustomerWrite(customer, pharmacy, "fixture", phone = "07000000000",
        openingDebt = 0, createdByName = "original creator", createdAt = instant)
    private fun seed(value: CloudCustomerWrite) {
        customerRows[value.id] = JSONObject(kotlinx.serialization.json.Json.encodeToString(CloudCustomerWrite.serializer(), value))
            .put("created_at", preciseCreation).put("updated_at", instant).toString()
    }
    private fun seed(value: CloudTransactionWrite) {
        rows[value.id] = JSONObject(kotlinx.serialization.json.Json.encodeToString(CloudTransactionWrite.serializer(), value))
            .put("created_at", preciseCreation).put("updated_at", instant).put("created_by", owner)
            .put("created_device_id", "00000000-0000-4000-8000-000000000099").toString()
    }

    @Test fun thePublishedUpsertReproducesHttp400WhenAMillisecondTimestampReplacesProtectedMicroseconds() = runBlocking {
        val client = client(); login(client); seed(customerWrite())
        val failure = runCatching { client.from("customers").upsert(customerWrite()) { onConflict = "id" } }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(400, (failure as io.github.jan.supabase.exceptions.RestException).statusCode)
        assertEquals(preciseCreation, JSONObject(customerRows.getValue(customer)).getString("created_at"))
    }

    @Test fun customerEditsUseOneConfirmedPatchAndPreserveTheServersExactCreationAndAuthor() = runBlocking {
        val client = client(); login(client); seed(customerWrite())
        val writer = CloudLedgerWriter(client, pharmacy)
        val value = customerWrite().copy(name = "edited", phone = null, notes = "updated notes", createdByName = "different local author")
        paths.clear(); writer.save(value)
        val saved = JSONObject(customerRows.getValue(customer))
        assertEquals(listOf("/rest/v1/customers"), paths.toList())
        assertTrue(postPaths.isEmpty())
        assertEquals(preciseCreation, saved.getString("created_at"))
        assertEquals("original creator", saved.getString("created_by_name"))
        assertTrue(saved.isNull("phone")); assertEquals("edited", saved.getString("name"))
        assertFalse(patches.single().has("created_at")); assertFalse(patches.single().has("pharmacy_id"))
        assertFalse(patches.single().has("id")); assertTrue(writePreferences.single().contains("return=representation"))
    }

    @Test fun financialEditsPreserveCreationActorOperationAndParentIdentities() = runBlocking {
        val client = client(); login(client); seed(debt())
        val oldOperation = "00000000-0000-4000-8000-000000000055"
        rows[entry] = JSONObject(rows.getValue(entry)).put("operation_id", oldOperation).toString()
        CloudLedgerWriter(client, pharmacy).save(debt(9000))
        val saved = JSONObject(rows.getValue(entry))
        assertEquals(9000L, saved.getLong("amount")); assertEquals(preciseCreation, saved.getString("created_at"))
        assertEquals(owner, saved.getString("created_by")); assertEquals(oldOperation, saved.getString("operation_id"))
        assertEquals(customer, saved.getString("customer_id"))
        assertEquals("00000000-0000-4000-8000-000000000099", saved.getString("created_device_id"))
        assertTrue(postPaths.isEmpty())
        assertTrue(listOf("created_at", "created_by", "created_device_id", "operation_id", "customer_id", "pharmacy_id", "id")
            .none { patches.single().has(it) })
    }

    @Test @Config(sdk = [26, 35])
    fun serverOffsetAcknowledgementsWorkOnOldAndNewSupportedAndroidVersions() = runBlocking {
        val client = client(); login(client); seed(debt()); canonicalOffsetReply.set(true)
        CloudLedgerWriter(client, pharmacy).save(debt(9000))
        assertEquals("2026-10-17T00:00:00.000000+00:00", JSONObject(rows.getValue(entry)).getString("occurred_at"))
        assertEquals(9000L, JSONObject(rows.getValue(entry)).getLong("amount"))
        assertEquals(preciseCreation, JSONObject(rows.getValue(entry)).getString("created_at"))
    }

    @Test fun oneHundredFortyNineOldQueuedRowsDrainWithoutRewritingIdentityOrDuplicatingMoney() = runBlocking {
        val client = client(); login(client); val writer = CloudLedgerWriter(client, pharmacy)
        val db = Room.inMemoryDatabaseBuilder<PharmacyLedgerDatabase>(ApplicationProvider.getApplicationContext<Context>())
            .setDriver(AndroidSQLiteDriver()).build()
        try {
            val time = java.time.Instant.parse(instant).toEpochMilli()
            val c = CustomerEntity(customer, "fixture", "07000000000", "", "", 0, "", time, "original creator")
            db.dao().insertCustomer(c); seed(customerWrite())
            repeat(148) { index ->
                val id = "00000000-0000-4000-8000-" + (index + 1000).toString().padStart(12, '0')
                db.dao().insertEntry(LedgerEntryEntity(id, customer, "DEBT", 7000, null, null, "", time))
                seed(debt().copy(id = id, operationId = id))
            }
            assertEquals(149, db.dao().cloudOutbox().size)
            DurableCloudDrain(db.dao()) { row ->
                if (row.kind == "CUSTOMER") writer.save(customerWrite())
                else writer.save(debt().copy(id = row.entityId, operationId = row.entityId))
            }.flush()
            assertTrue(db.dao().cloudOutbox().isEmpty()); assertEquals(148, rows.size)
            assertEquals(148 * 7000L, rows.values.sumOf { JSONObject(it).getLong("amount") })
            assertTrue(rows.values.all { JSONObject(it).getString("created_at") == preciseCreation })
            assertEquals(149, patches.size); assertTrue(postPaths.isEmpty())
        } finally { db.close() }
    }

    @Test fun lostInsertReplyAndOfflineRetryKeepTheOriginalQueueRevisionAndOnlyOneServerRow() = runBlocking {
        val client = client(); login(client); val writer = CloudLedgerWriter(client, pharmacy)
        val db = Room.inMemoryDatabaseBuilder<PharmacyLedgerDatabase>(ApplicationProvider.getApplicationContext<Context>())
            .setDriver(AndroidSQLiteDriver()).build()
        try {
            val time = java.time.Instant.parse(instant).toEpochMilli()
            db.dao().insertCustomer(CustomerEntity(customer, "fixture", null, "", "", 0, "", time), "REMOTE")
            db.dao().insertEntry(LedgerEntryEntity(entry, customer, "DEBT", 7000, null, null, "", time))
            val original = db.dao().cloudOutbox()
            rejectWrites.set(true)
            assertTrue(runCatching { DurableCloudDrain(db.dao()) { writer.save(debt()) }.flush() }.isFailure)
            assertEquals(original, db.dao().cloudOutbox()); assertTrue(rows.isEmpty())
            rejectWrites.set(false); loseInsertResponse.set(true)
            assertTrue(runCatching { DurableCloudDrain(db.dao()) { writer.save(debt()) }.flush() }.isFailure)
            assertEquals(original, db.dao().cloudOutbox())
            DurableCloudDrain(db.dao()) { writer.save(debt()) }.flush()
            assertTrue(db.dao().cloudOutbox().isEmpty()); assertEquals(1, rows.size)
            assertEquals(1, postPaths.size)
        } finally { db.close() }
    }

    @Test fun aSoftDeletedOrDifferentParentRecordIsNeverResurrectedOrMoved() = runBlocking {
        val client = client(); login(client); val writer = CloudLedgerWriter(client, pharmacy)
        seed(debt())
        rows[entry] = JSONObject(rows.getValue(entry)).put("customer_id", owner).toString()
        val original = rows.getValue(entry)
        assertTrue(runCatching { writer.save(debt(9000)) }.isFailure)
        assertEquals(original, rows.getValue(entry))
        seed(customerWrite())
        customerRows[customer] = JSONObject(customerRows.getValue(customer)).put("deleted_at", instant).toString()
        val deleted = customerRows.getValue(customer)
        assertTrue(runCatching { writer.save(customerWrite()) }.isFailure)
        assertEquals(deleted, customerRows.getValue(customer))
    }

    @Test fun bootstrapCreationRacesReadBackInsteadOfOverwritingAnotherDevicesFinancialValues() = runBlocking {
        val client = client(); login(client); val writer = CloudLedgerWriter(client, pharmacy)
        seed(debt())
        writer.save(debt(), createFirst = true)
        assertTrue(patches.isEmpty())
        val original = rows.getValue(entry)
        assertTrue(runCatching { writer.save(debt(9000), createFirst = true) }.isFailure)
        assertEquals(original, rows.getValue(entry)); assertTrue(patches.isEmpty())
    }

    @Test fun aSuccessfulHttpStatusWithDifferentReturnedFinancialValuesCannotAcknowledgeTheQueue() = runBlocking {
        val client = client(); login(client); val writer = CloudLedgerWriter(client, pharmacy); seed(debt())
        val db = Room.inMemoryDatabaseBuilder<PharmacyLedgerDatabase>(ApplicationProvider.getApplicationContext<Context>())
            .setDriver(AndroidSQLiteDriver()).build()
        try {
            val time = java.time.Instant.parse(instant).toEpochMilli()
            db.dao().insertCustomer(CustomerEntity(customer, "fixture", null, "", "", 0, "", time), "REMOTE")
            db.dao().insertEntry(LedgerEntryEntity(entry, customer, "DEBT", 7000, null, null, "", time))
            val original = db.dao().cloudOutbox(); wrongAcknowledgement.set(true)
            assertTrue(runCatching { DurableCloudDrain(db.dao()) { writer.save(debt()) }.flush() }.isFailure)
            assertEquals(original, db.dao().cloudOutbox())
            wrongAcknowledgement.set(false)
            DurableCloudDrain(db.dao()) { writer.save(debt()) }.flush()
            assertTrue(db.dao().cloudOutbox().isEmpty()); assertEquals(1, rows.size)
        } finally { db.close() }
    }
}

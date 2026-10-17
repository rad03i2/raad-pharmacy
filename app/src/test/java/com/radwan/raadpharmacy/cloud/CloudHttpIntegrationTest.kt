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
    private val loseResponse = AtomicBoolean(false)
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
                    if (exchange.requestMethod == "GET") {
                        val id = exchange.requestURI.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("id=eq.") }?.substringAfter("id=eq.")
                        reply(exchange, 200, JSONArray(table.values.map(::JSONObject).filter { id == null || it.getString("id") == id }).toString())
                    } else {
                    postPaths += exchange.requestURI.path
                    val preference = exchange.requestHeaders.getFirst("Prefer").orEmpty()
                    writePreferences += preference
                    val body = exchange.requestBody.bufferedReader().use { it.readText() }
                    val data = if (body.trimStart().startsWith("[")) JSONArray(body).getJSONObject(0) else JSONObject(body)
                    if (table.containsKey(data.getString("id")) && "merge-duplicates" !in preference)
                        reply(exchange, 409, """{"code":"23505","message":"fixture duplicate key"}""")
                    else {
                    data.put("updated_at", instant); table[data.getString("id")] = data.toString()
                    if (loseResponse.compareAndSet(true, false)) exchange.close() else reply(exchange, 201, "")
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
}

package com.radwan.raadpharmacy.cloud

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
import io.ktor.client.statement.bodyAsText
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    private val agents = CopyOnWriteArrayList<String>()
    private val loseResponse = AtomicBoolean(false)
    private val rejectLogin = AtomicBoolean(false)
    private val owner = "00000000-0000-4000-8000-000000000001"
    private val pharmacy = "00000000-0000-4000-8000-000000000002"
    private val customer = "00000000-0000-4000-8000-000000000003"
    private val entry = "00000000-0000-4000-8000-000000000004"
    private val instant = "2026-10-17T00:00:00Z"
    private val url get() = "http://127.0.0.1:${server.address.port}"

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            agents += exchange.requestHeaders.getFirst("User-Agent").orEmpty()
            when (exchange.requestURI.path) {
                "/slow" -> { Thread.sleep(350); runCatching { reply(exchange, 200, "{}") } }
                "/auth/v1/token" -> if (rejectLogin.get()) reply(exchange, 400,
                    """{"code":"invalid_credentials","msg":"Invalid login credentials"}""")
                else reply(exchange, 200, JSONObject().put("access_token", "fixture-access-token")
                    .put("refresh_token", "fixture-refresh-token").put("token_type", "bearer").put("expires_in", 3600)
                    .put("user", JSONObject().put("id", owner).put("aud", "authenticated").put("role", "authenticated")
                        .put("email", "fixture@example.invalid").put("created_at", instant)).toString())
                "/rest/v1/profiles" -> reply(exchange, 200, """[{"id":"$owner","pharmacy_id":"$pharmacy","display_name":"fixture","role":"MANAGER"}]""")
                "/rest/v1/transactions" -> if (exchange.requestMethod == "GET")
                    reply(exchange, 200, JSONArray(rows.values.map(::JSONObject)).toString())
                else {
                    val body = exchange.requestBody.bufferedReader().use { it.readText() }
                    val data = if (body.trimStart().startsWith("[")) JSONArray(body).getJSONObject(0) else JSONObject(body)
                    data.put("updated_at", instant); rows[data.getString("id")] = data.toString()
                    if (loseResponse.compareAndSet(true, false)) exchange.close() else reply(exchange, 201, "")
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
}

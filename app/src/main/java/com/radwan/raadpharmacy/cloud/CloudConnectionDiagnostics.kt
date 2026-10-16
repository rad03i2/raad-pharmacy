package com.radwan.raadpharmacy.cloud

import android.content.Context
import com.radwan.raadpharmacy.BuildConfig
import io.github.jan.supabase.annotations.SupabaseInternal
import io.ktor.client.statement.bodyAsText
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import org.json.JSONObject

internal enum class CloudFailureCode { DNS, TLS, CONNECT, TIMEOUT, HTTP, SESSION, DECODE, TRANSPORT, UNKNOWN }
internal data class CloudConnectionFailure(val path: String, val code: CloudFailureCode,
    val status: Int? = null, val at: Long = System.currentTimeMillis())

/** Only allowlisted metadata. Never persist exception text, URLs, identifiers or credentials. */
internal object CloudConnectionDiagnostics {
    private var preferences: android.content.SharedPreferences? = null
    private val mutableFailure = MutableStateFlow<CloudConnectionFailure?>(null)
    val failure = mutableFailure.asStateFlow()
    private val mutableProbe = MutableStateFlow<String?>(null)
    val probeResult = mutableProbe.asStateFlow()

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("raad_connection_diagnostics", Context.MODE_PRIVATE)
        runCatching {
            val obj = preferences?.getString("failure", null)?.let(::JSONObject) ?: return@runCatching
            mutableFailure.value = CloudConnectionFailure(obj.getString("path"),
                CloudFailureCode.valueOf(obj.getString("code")), obj.optInt("status").takeIf { it > 0 }, obj.getLong("at"))
        }
    }

    fun classify(error: Throwable): CloudConnectionFailure {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        // supabase-kt 3.8 wraps transport exceptions without preserving their cause.
        // Match fixed markers if needed; the original text is never saved or displayed.
        val markers = causes.joinToString(" ") { it.javaClass.simpleName + " " + it.message.orEmpty() }.lowercase()
        val status = causes.filterIsInstance<RestException>().firstOrNull()?.statusCode
        val code = when {
            status != null -> CloudFailureCode.HTTP
            causes.any { it is UnknownHostException } || "unknownhost" in markers || "unable to resolve host" in markers -> CloudFailureCode.DNS
            causes.any { it is SSLException } || "sslhandshake" in markers || "trust anchor" in markers || "certificate" in markers -> CloudFailureCode.TLS
            causes.any { it is SocketTimeoutException || it is kotlinx.coroutines.TimeoutCancellationException } || "timeout" in markers || "timed out" in markers -> CloudFailureCode.TIMEOUT
            causes.any { it is ConnectException } || "connection refused" in markers -> CloudFailureCode.CONNECT
            "session" in markers || "jwt" in markers -> CloudFailureCode.SESSION
            "serializ" in markers || "json" in markers || "decode" in markers -> CloudFailureCode.DECODE
            causes.any { it is java.io.IOException } || "httprequestexception" in markers -> CloudFailureCode.TRANSPORT
            else -> CloudFailureCode.UNKNOWN
        }
        return CloudConnectionFailure("request", code, status)
    }

    @Synchronized fun record(path: String, error: Throwable) {
        if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) return
        val route = when {
            "health" in path -> "health"
            "login" in path || "auth/" in path || "session" in path -> "auth"
            "transaction" in path || "customer" in path || "financial" in path -> "ledger"
            "team" in path || "profiles" in path || "presence" in path -> "team"
            "notification" in path || "message" in path || "alert" in path -> "messages"
            else -> "runtime"
        }
        val next = classify(error).copy(path = route)
        val previous = mutableFailure.value
        // Keep the engine's specific cause when the SDK immediately wraps the same failure.
        val saved = if (next.code in setOf(CloudFailureCode.UNKNOWN, CloudFailureCode.TRANSPORT) &&
            previous != null && next.at - previous.at in 0..1_000 && previous.code !in setOf(CloudFailureCode.UNKNOWN, CloudFailureCode.TRANSPORT))
            previous.copy(path = route) else next
        mutableFailure.value = saved
        preferences?.edit()?.putString("failure", JSONObject().put("path", saved.path)
            .put("code", saved.code.name).put("status", saved.status ?: 0).put("at", saved.at).toString())?.apply()
    }

    fun report(): String = buildString {
        append("Raad ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); Android ${android.os.Build.VERSION.SDK_INT}")
        failure.value?.let { append("\n${it.path}: ${it.code}${it.status?.let { s -> " $s" } ?: ""}; at=${it.at}") }
        probeResult.value?.let { append("\n$it") }
    }

    /** Two read-only health requests. No account change, financial write or automatic message. */
    @OptIn(SupabaseInternal::class)
    suspend fun probe() = withContext(Dispatchers.IO) {
        supervisorScope {
            val sdk = async {
                try {
                    val response = SupabaseProvider.client.httpClient.request(BuildConfig.SUPABASE_URL + "/auth/v1/health") { }
                    response.bodyAsText()
                    "SDK HTTP ${response.status.value}"
                } catch (error: Exception) {
                    error.rethrowCloudCancellation()
                    "SDK ${classify(error).code}"
                }
            }
            val platform = async {
                var connection: HttpsURLConnection? = null
                try {
                    connection = URL(BuildConfig.SUPABASE_URL + "/auth/v1/health").openConnection() as HttpsURLConnection
                    connection.connectTimeout = 8_000; connection.readTimeout = 8_000; connection.instanceFollowRedirects = false
                    connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                    connection.setRequestProperty("User-Agent", "RaadPharmacy/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); probe")
                    val status = connection.responseCode
                    (if (status < 400) connection.inputStream else connection.errorStream)?.close()
                    "Android HTTP $status"
                } catch (error: Exception) {
                    error.rethrowCloudCancellation()
                    "Android ${classify(error).code}"
                } finally { connection?.disconnect() }
            }
            mutableProbe.value = "${sdk.await()}; ${platform.await()}"
        }
    }
}

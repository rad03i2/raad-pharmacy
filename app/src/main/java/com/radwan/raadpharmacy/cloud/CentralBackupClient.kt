package com.radwan.raadpharmacy.cloud

import com.radwan.raadpharmacy.BuildConfig
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class CentralBackupRun(val date: String?, val state: String, val bytes: Long, val error: String?)
data class CentralBackupStatus(
    val configured: Boolean = false, val health: String = "NOT_CONFIGURED",
    val phase: String = "NOT_CONFIGURED", val lastSuccess: String? = null,
    val lastAttempt: String? = null, val nextExpected: String? = null,
    val retained: Int = 0, val bytes: Long = 0, val quotaUsed: Long = 0, val quotaLimit: Long = 0,
    val restoreTested: String? = null, val owner: String? = null, val error: String? = null,
    val canViewHistory: Boolean = false, val canRequest: Boolean = false,
    val automatic: Boolean = false, val history: List<CentralBackupRun> = emptyList()
)

/** Uses only the existing Supabase user session. No Google/server secrets or file URLs. */
object CentralBackupClient {
    internal fun parse(json: JSONObject): CentralBackupStatus {
        fun text(name: String): String? = if (json.isNull(name)) null else json.optString(name).takeIf { it.isNotBlank() }
        val admin = json.optBoolean("can_view_history")
        val rows = json.optJSONArray("history")
        val history = if (!admin || rows == null) emptyList() else (0 until minOf(60, rows.length())).map { index ->
            val row = rows.getJSONObject(index)
            CentralBackupRun(row.optString("created_at").takeIf { it.isNotBlank() },
                row.optString("state"), row.optLong("bytes").coerceAtLeast(0),
                if (row.isNull("error_code")) null else row.optString("error_code"))
        }
        return CentralBackupStatus(json.optBoolean("configured"), json.optString("health", "NOT_CONFIGURED"),
            json.optString("phase", "NOT_CONFIGURED"), text("last_success_at"), text("last_attempt_at"), text("next_expected_at"),
            json.optInt("retained_count").coerceAtLeast(0), json.optLong("total_bytes").coerceAtLeast(0),
            json.optLong("quota_used").coerceAtLeast(0), json.optLong("quota_limit").coerceAtLeast(0),
            text("restore_tested_at"), if (admin) text("owner_email") else null, if (admin) text("last_error_code") else null,
            admin, admin && json.optBoolean("can_request_backup"), json.optBoolean("automatic_enabled"), history)
    }

    suspend fun load(): CentralBackupStatus = parse(request("GET"))
    suspend fun requestBackup() { request("POST") }

    private suspend fun request(method: String): JSONObject = withContext(Dispatchers.IO) {
        val auth = SupabaseProvider.client.auth
        auth.awaitInitialization()
        val session = auth.currentSessionOrNull() ?: error("يرجى تسجيل الدخول لمشاهدة حالة النسخ المركزي.")
        val owner = session.user?.id ?: error("يرجى تسجيل الدخول.")
        val connection = URL(BuildConfig.SUPABASE_URL + "/functions/v1/cloud-backup").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer " + session.accessToken)
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Accept", "application/json")
            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write("{}".toByteArray()) }
            }
            val code = connection.responseCode
            if (code == 404 && method == "GET") return@withContext JSONObject()
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val data = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    check(output.size() + count <= 512 * 1024) { "استجابة الخدمة غير صالحة." }
                    output.write(chunk,0,count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            check(auth.currentSessionOrNull()?.user?.id == owner) { "تغير حساب الدخول. أعد تحميل الحالة." }
            if (code == 503 && method == "GET" && runCatching { JSONObject(data.toString(Charsets.UTF_8)).optString("error") }.getOrNull() == "service_not_configured") return@withContext JSONObject()
            check(code in 200..299) { when (code) {
                401 -> "انتهت جلسة الدخول. أعد تسجيل الدخول ثم حاول مجددًا."
                403 -> "ليست لديك صلاحية تنفيذ هذا الإجراء."
                409 -> "يوجد طلب حديث أو نسخة قيد التشغيل. حاول لاحقًا."
                else -> "تعذر الاتصال بخدمة النسخ المركزي. آخر نسخة سليمة تبقى محفوظة."
            } }
            JSONObject(data.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}

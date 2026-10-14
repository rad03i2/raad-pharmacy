package com.radwan.raadpharmacy.whatsapp

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.util.UUID

internal const val PHONE_WHATSAPP_PROTOCOL = "raad-phone-whatsapp-trial-v1"
internal const val PHONE_WHATSAPP_PORT = 39761

internal data class PhoneWhatsappStatus(
    val state: String = "unavailable",
    val phone: String? = null,
    val pairingCode: String? = null,
    val busy: Boolean = false
)

internal data class PhoneWhatsappTrial(val id: String, val phone: String, val senderPhone: String?, val state: String, val updatedAt: Long)

/** Local-only settings. Android backup includes only the ledger, never these credentials. */
internal class PhoneWhatsappStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("raad_phone_whatsapp_trial_v1", Context.MODE_PRIVATE)
    @Synchronized fun token(): String {
        prefs.getString("token", null)?.takeIf { Regex("^[a-f0-9]{64}$").matches(it) }?.let { return it }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        check(prefs.edit().putString("token", token).commit()) { "تعذر حفظ إعداد المساعد على الهاتف." }
        return token
    }
    fun lastTrialId(): String? = prefs.getString("last_trial_id", null)?.takeIf {
        runCatching { UUID.fromString(it) }.isSuccess
    }
    fun recordTrial(id: String) {
        require(UUID.fromString(id).version() == 4)
        check(prefs.edit().putString("last_trial_id", id).remove("verified_at").commit()) {
            "تعذر حفظ معرّف تجربة الإرسال."
        }
    }
    fun confirmReceived(senderPhone: String) {
        check(lastTrialId() != null)
        check(prefs.edit().putLong("verified_at", System.currentTimeMillis()).putString("verified_sender", senderPhone).commit()) { "تعذر حفظ تأكيد التجربة." }
    }
    fun verifiedAt(senderPhone: String?): Long = if (senderPhone != null && senderPhone == prefs.getString("verified_sender", null))
        prefs.getLong("verified_at", 0L) else 0L
    fun clearConfirmation() { prefs.edit().remove("verified_at").remove("verified_sender").remove("last_trial_id").apply() }
}

internal object PhoneWhatsappSetup {
    fun command(context: Context, token: String): String =
        context.assets.open("raad-phone-whatsapp.tgz").use { command(it.readBytes(), token) }

    internal fun command(archive: ByteArray, token: String): String {
        require(Regex("^[a-f0-9]{64}$").matches(token))
        require(archive.isNotEmpty() && archive.size <= 75_000)
        val payload = Base64.encodeToString(archive, Base64.NO_WRAP)
        // Both interpolated values use restricted alphabets; no customer input enters the shell.
        return """
            pkg update -y &&
            pkg install -y nodejs-lts &&
            raad_bridge_dir="${'$'}HOME/.raad-whatsapp" &&
            mkdir -p "${'$'}raad_bridge_dir" &&
            chmod 700 "${'$'}raad_bridge_dir" &&
            printf '%s' '$payload' | base64 -d | tar -xz -C "${'$'}raad_bridge_dir" &&
            sh "${'$'}raad_bridge_dir/install.sh" '$token'
        """.trimIndent()
    }
    const val RESUME_COMMAND = "termux-wake-lock && node \"${'$'}HOME/.raad-whatsapp/index.mjs\""
}

/** Fixed loopback destination, no redirects/proxy, bounded responses and no automatic retry. */
internal class PhoneWhatsappClient(private val token: () -> String, private val port: Int = PHONE_WHATSAPP_PORT) {
    suspend fun status(): PhoneWhatsappStatus = parseStatus(request("GET", "/status"))
    suspend fun connect(phone: String) = parseStatus(request("POST", "/connect", JSONObject().put("phone", phone)))
    suspend fun unlink() = parseStatus(request("POST", "/unlink", JSONObject()))
    suspend fun stop() = parseStatus(request("POST", "/stop", JSONObject()))
    suspend fun trial(id: String): PhoneWhatsappTrial {
        require(UUID.fromString(id).version() == 4)
        return parseTrial(request("GET", "/jobs/$id").getJSONObject("job"))
    }
    suspend fun sendTrial(id: String, phone: String, image: PhoneWhatsappTestImage.Payload): PhoneWhatsappTrial {
        require(UUID.fromString(id).version() == 4)
        val body = JSONObject().put("requestId", id).put("phone", phone)
            .put("imageBase64", Base64.encodeToString(image.image, Base64.NO_WRAP))
            .put("thumbnailBase64", Base64.encodeToString(image.thumbnail, Base64.NO_WRAP))
        return parseTrial(request("POST", "/test", body).getJSONObject("job"))
    }
    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val key = token()
        require(Regex("^[a-f0-9]{64}$").matches(key))
        val connection = URL("http://127.0.0.1:$port$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 4_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 300_000)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val content = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 64_000) { "رد المساعد أكبر من الحد المسموح." }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }.orEmpty()
            val json = runCatching { JSONObject(content) }.getOrElse { throw IllegalStateException("تعذر قراءة رد المساعد.") }
            check(json.optString("protocol") == PHONE_WHATSAPP_PROTOCOL) { "أعد إعداد المساعد باستخدام الأمر من هذا الإصدار." }
            check(status in 200..299) { json.optString("error", "تعذر تنفيذ طلب المساعد.") }
            json
        } finally { connection.disconnect() }
    }
    private fun parseStatus(json: JSONObject) = PhoneWhatsappStatus(
        state = json.optString("state", "unavailable"), phone = json.optionalString("phone"),
        pairingCode = json.optionalString("pairingCode"), busy = json.optBoolean("busy", false)
    )
    private fun parseTrial(json: JSONObject) = PhoneWhatsappTrial(
        id = json.getString("id"), phone = json.getString("phone"), senderPhone = json.optionalString("senderPhone"),
        state = json.getString("state"), updatedAt = json.getLong("updatedAt")
    )
}

private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

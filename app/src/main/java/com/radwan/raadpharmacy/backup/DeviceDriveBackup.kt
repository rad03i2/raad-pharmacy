package com.radwan.raadpharmacy.backup

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun <T> Task<T>.awaitBackupTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

internal class DriveAuthorizationNeeded : Exception("أعد ربط حساب Google للسماح بتحديث النسخة.")
internal class DeviceDriveBackup(private val token: String,
    private val connectionOverride: ((String, String) -> HttpURLConnection)? = null) {
    private fun connection(path: String, method: String = "GET"): HttpURLConnection =
        (connectionOverride?.invoke(path, method) ?: (URL("https://www.googleapis.com/$path").openConnection() as HttpURLConnection)).apply {
            requestMethod = method; connectTimeout = 20_000; readTimeout = 40_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
        }
    private fun response(connection: HttpURLConnection): ByteArray {
        val status = connection.responseCode
        if (status !in 200..299) {
            if (status == 401) throw DriveAuthorizationNeeded()
            val reason = runCatching { connection.errorStream?.use { it.readBackupBytes() }?.let { JSONObject(String(it)).getJSONObject("error").toString() } }.getOrNull().orEmpty()
            throw IllegalStateException(when {
                reason.contains("accessNotConfigured") || reason.contains("SERVICE_DISABLED") -> "خدمة Google Drive غير مفعّلة في إعدادات التطبيق."
                reason.contains("storageQuotaExceeded") -> "مساحة حساب Google Drive ممتلئة."
                status == 403 -> "تعذر الوصول إلى Google Drive. راجع إذن الحساب وإعدادات التطبيق."
                status == 404 -> "النسخة أو مجلد Google Drive غير متاح."
                else -> "تعذر الاتصال بـ Google Drive. ستُعاد المحاولة تلقائيًا."
            })
        }
        return connection.inputStream.use { it.readBackupBytes() }
    }
    private fun json(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val c = connection(path, method)
        try {
            if (body != null) {
                c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val bytes = response(c)
            return if (bytes.isEmpty()) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8))
        } finally { c.disconnect() }
    }
    fun email(): String = json("drive/v3/about?fields=user").getJSONObject("user").getString("emailAddress")
    private fun files(query: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var page: String? = null
        do {
            val response = json("drive/v3/files?pageSize=1000&fields=nextPageToken,files(id,name,size,createdTime,md5Checksum,appProperties)&q=${encode(query)}" +
                (page?.let { "&pageToken=${encode(it)}" } ?: ""))
            val rows = response.getJSONArray("files")
            for (i in 0 until rows.length()) result.add(rows.getJSONObject(i))
            page = response.optString("nextPageToken").takeIf { it.isNotBlank() }
            require(result.size <= 10_000) { "عدد النسخ كبير جدًا. افتح مجلد Google Drive مباشرة." }
        } while (page != null)
        return result
    }
    private fun folder(device: String): String {
        val parents = files("trashed = false and mimeType = 'application/vnd.google-apps.folder' and appProperties has { key='raadBackupRoot' and value='v3' }")
        val parent = parents.firstOrNull()?.getString("id") ?: json("drive/v3/files?fields=id", "POST",
            JSONObject().put("name", PortableBackup.FOLDER).put("mimeType", "application/vnd.google-apps.folder")
                .put("appProperties", JSONObject().put("raadBackupRoot", "v3"))).getString("id")
        val children = files("trashed = false and mimeType = 'application/vnd.google-apps.folder' and '$parent' in parents and appProperties has { key='raadDevice' and value='$device' }")
        return children.firstOrNull()?.getString("id") ?: json("drive/v3/files?fields=id", "POST",
            JSONObject().put("name", "هاتف-${device.take(8)}").put("mimeType", "application/vnd.google-apps.folder")
                .put("parents", JSONArray().put(parent)).put("appProperties", JSONObject().put("raadDevice", device))).getString("id")
    }
    fun upload(device: String, name: String, bytes: ByteArray): String {
        PortableBackup.decode(bytes)
        require(device.matches(Regex("[a-zA-Z0-9-]+")))
        val expected = md5(bytes)
        val existing = files("trashed = false and appProperties has { key='raadPortableBackup' and value='v3' } and appProperties has { key='raadDevice' and value='$device' } and name='$name'")
            .firstOrNull { it.optString("md5Checksum") == expected && it.optLong("size", -1) == bytes.size.toLong() }
        val id = existing?.getString("id") ?: run {
            val metadata = JSONObject().put("name", name).put("mimeType", "application/octet-stream")
                .put("parents", JSONArray().put(folder(device)))
                .put("appProperties", JSONObject().put("raadPortableBackup", "v3").put("raadDevice", device))
            val boundary = "raad-${UUID.randomUUID()}"
            val header = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
                "--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8)
            val footer = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            val c = connection("upload/drive/v3/files?uploadType=multipart&fields=id,size,md5Checksum", "POST")
            try {
                c.doOutput = true; c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
                c.setFixedLengthStreamingMode(header.size.toLong() + bytes.size + footer.size)
                c.outputStream.use { it.write(header); it.write(bytes); it.write(footer) }
                val saved = JSONObject(String(response(c), Charsets.UTF_8))
                check(saved.optString("md5Checksum") == expected && saved.optLong("size", -1) == bytes.size.toLong()) { "لم يكتمل التحقق من النسخة السحابية." }
                saved.getString("id")
            } finally { c.disconnect() }
        }
        // Keep the latest three complete files per device. Other phones are never overwritten.
        runCatching {
            files("trashed = false and appProperties has { key='raadPortableBackup' and value='v3' } and appProperties has { key='raadDevice' and value='$device' }")
                .filter { it.getString("id") != id }.sortedByDescending { it.getString("name") }.drop(2)
                .forEach { json("drive/v3/files/${it.getString("id")}", "PATCH", JSONObject().put("trashed", true)) }
        }
        return id
    }
    fun history(): List<PortableBackupItem> = files("trashed = false and appProperties has { key='raadPortableBackup' and value='v3' }")
        .filter { PhoneBackupTarget.validName(it.getString("name")) }
        .map { PhoneBackupTarget.item(it.getString("id"), it.getString("name"), it.optLong("size"), "drive") }
        .sortedByDescending { it.createdAt }
    fun download(id: String): ByteArray {
        require(id.matches(Regex("[a-zA-Z0-9_-]+")))
        val c = connection("drive/v3/files/$id?alt=media")
        return try { response(c).also { PortableBackup.decode(it) } } finally { c.disconnect() }
    }
    companion object {
        fun request(email: String? = null): AuthorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file")))
            .apply { email?.let { setAccount(Account(it, "com.google")) } }.build()
        suspend fun authorized(context: Context, email: String): DeviceDriveBackup {
            val result = Identity.getAuthorizationClient(context).authorize(request(email)).awaitBackupTask()
            if (result.hasResolution()) throw DriveAuthorizationNeeded()
            return DeviceDriveBackup(checkNotNull(result.accessToken) { "تعذر الحصول على إذن Google Drive." })
        }
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        internal fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

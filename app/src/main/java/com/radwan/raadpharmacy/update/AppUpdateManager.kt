package com.radwan.raadpharmacy.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.radwan.raadpharmacy.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** Only official signed stable GitHub Releases may be installed, never a CI artifact. */
object AppUpdateManager {
    private const val LATEST = "https://api.github.com/repos/rad03i2/raad-pharmacy/releases/latest"
    private const val MIME = "application/vnd.android.package-archive"
    private const val PREFS = "raad_app_update_v1"
    private const val MAX_APK = 150L * 1024L * 1024L
    private val VERSION = Regex("^v[0-9]+\\.[0-9]+\\.[0-9]+$")
    private val SHA = Regex("(?i)^[a-f0-9]{64}$")
    private val CODE_FIELD = Regex("(?m)^VersionCode: ([0-9]+)\\s*$")
    private val CERT_FIELD = Regex("(?m)^SigningCertificateSHA256: ([a-fA-F0-9]{64})\\s*$")

    data class Release(val tag: String, val versionCode: Long, val notes: String,
        val size: Long, val apkUrl: String, val signer: String, val sha256: String)
    data class Session(val id: Long, val tag: String, val code: Long, val sha256: String)
    data class Progress(val finished: Boolean, val percent: Int, val failed: String? = null)

    suspend fun checkForUpdate(context: Context): Release? = withContext(Dispatchers.IO) {
        val (status, data) = readHttps(LATEST, 1_000_000)
        if (status == 404) return@withContext null
        check(status == 200) { "تعذر فحص التحديثات (HTTP $status)." }
        val json = JSONObject(data)
        check(!json.optBoolean("draft", true) && !json.optBoolean("prerelease", true)) {
            "هذا الإصدار غير مستقر."
        }
        val tag = json.getString("tag_name")
        check(VERSION.matches(tag)) { "رقم النسخة المنشورة غير صحيح." }
        val body = json.optString("body")
        val code = CODE_FIELD.find(body)?.groupValues?.get(1)?.toLongOrNull()
        val signer = CERT_FIELD.find(body)?.groupValues?.get(1)?.lowercase(Locale.US)
        check(code != null && code > 0 && signer != null) {
            "بيانات التوقيع غير مكتملة. لن يتم تحميل ملف غير موثوق."
        }
        if (code <= BuildConfig.VERSION_CODE.toLong()) return@withContext null
        // Reject an incompatible APK before downloading it, not after risking a failed install.
        check(signer == installedSigner(context)) {
            "هذه النسخة موقّعة بمفتاح مختلف عن النسخة المثبتة. " +
                "التحديث فوقها غير ممكن دون مفتاحها الأصلي، ولن تُحذف بياناتك."
        }
        val apkName = "Raad-Pharmacy-$tag.apk"
        val hashName = "$apkName.sha256"
        val assets = json.getJSONArray("assets")
        fun asset(name: String): JSONObject? {
            for (i in 0 until assets.length()) {
                val item = assets.getJSONObject(i)
                if (item.optString("name") == name) return item
            }
            return null
        }
        val apk = asset(apkName) ?: error("ملف APK غير منشور لهذا الإصدار.")
        val hash = asset(hashName) ?: error("ملف بصمة APK غير منشور.")
        val size = apk.getLong("size")
        check(size in 1024..MAX_APK) { "حجم ملف التحديث غير مقبول." }
        val apkUrl = apk.getString("browser_download_url")
        val hashUrl = hash.getString("browser_download_url")
        verifyGitHubAssetUrl(apkUrl, tag, apkName)
        verifyGitHubAssetUrl(hashUrl, tag, hashName)
        val (hashStatus, hashText) = readHttps(hashUrl, 4096)
        check(hashStatus == 200) { "فشل الحصول على البصمة الأمنية." }
        val expectedHash = hashText.trim().split(Regex("\\s+")).firstOrNull()
            ?.lowercase(Locale.US).orEmpty()
        check(SHA.matches(expectedHash)) { "ملف البصمة غير صالح." }
        Release(tag, code, body.lineSequence()
            .filterNot { it.startsWith("VersionCode:") || it.startsWith("SigningCertificateSHA256:") }
            .joinToString("\n").trim().take(2500), size, apkUrl, signer, expectedHash)
    }

    private fun verifyGitHubAssetUrl(link: String, tag: String, name: String) {
        val uri = Uri.parse(link)
        check(uri.scheme == "https" && uri.host == "github.com" &&
            uri.path == "/rad03i2/raad-pharmacy/releases/download/$tag/$name" &&
            uri.encodedQuery == null && uri.encodedFragment == null) {
            "مصدر APK غير موثوق."
        }
    }

    /** Bounded HTTP reads: no accidental large JSON or APK in memory. */
    private fun readHttps(link: String, max: Int): Pair<Int, String> {
        var target = URL(link)
        repeat(6) {
            check(target.protocol == "https") { "الاتصال غير آمن." }
            val conn = (target.openConnection() as HttpURLConnection)
            try {
                conn.connectTimeout = 12_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Raad-Pharmacy-Updater")
                val response = conn.responseCode
                if (response in 300..399) {
                    val newUrl = URL(target, conn.getHeaderField("Location") ?: error("رابط تحويل ناقص."))
                    check(newUrl.protocol == "https" &&
                        (newUrl.host == "github.com" || newUrl.host.endsWith(".githubusercontent.com") ||
                            newUrl.host == "objects.githubusercontent.com")) { "تحويل غير موثوق." }
                    target = newUrl
                } else {
                    if (response == 404) return response to ""
                    val stream = if (response >= 400) conn.errorStream else conn.inputStream
                    val text = stream?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(4096)
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            check(output.size() + n <= max) { "استجابة التحديث أكبر من المتوقع." }
                            output.write(chunk, 0, n)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }.orEmpty()
                    return response to text
                }
            } finally { conn.disconnect() }
        }
        error("عدد تحويلات التنزيل تجاوز الحد الآمن.")
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(context: Context, archive: String? = null): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                    else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        return (if (archive == null) pm.getPackageInfo(context.packageName, flags)
                else pm.getPackageArchiveInfo(archive, flags))
            ?: error("تعذر قراءة شهادة توقيع التطبيق.")
    }

    @Suppress("DEPRECATION")
    private fun signedBytes(info: PackageInfo): ByteArray {
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
                      else info.signatures
        check(signers?.size == 1) { "لا يمكن التحقق من شهادة التوقيع." }
        return signers[0].toByteArray()
    }

    private fun bytesHex(bytes: ByteArray) =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun certificateDigest(info: PackageInfo): String =
        bytesHex(MessageDigest.getInstance("SHA-256").digest(signedBytes(info)))

    fun installedSigner(context: Context): String = certificateDigest(packageInfo(context))

    @Suppress("DEPRECATION")
    private fun apkCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun targetFile(context: Context, tag: String): File {
        check(VERSION.matches(tag))
        return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("تعذر الوصول إلى مساحة التنزيل."), "Raad-Pharmacy-$tag.apk")
    }

    fun pending(context: Context): Session? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong("id", -1)
        val tag = prefs.getString("tag", "").orEmpty()
        val sha = prefs.getString("sha", "").orEmpty()
        val code = prefs.getLong("code", 0)
        return if (id > 0 && VERSION.matches(tag) && SHA.matches(sha) &&
            code > BuildConfig.VERSION_CODE) Session(id, tag, code, sha) else null
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun begin(context: Context, release: Release): Session {
        check(release.versionCode > BuildConfig.VERSION_CODE)
        check(release.signer == installedSigner(context)) { "الشهادة غير متطابقة." }
        pending(context)?.let { return it }
        val file = targetFile(context, release.tag)
        if (file.exists()) check(file.delete()) { "تعذر حذف تنزيل تالف سابق." }
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setMimeType("application/vnd.android.package-archive")
            .setTitle("تحديث دفتر صيدلية رعد")
            .setDescription("تنزيل آمن مع الحفاظ على بيانات التطبيق")
            .setAllowedOverMetered(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, file.name)
        val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        val session = Session(id, release.tag, release.versionCode, release.sha256)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("id", id)
            .putString("tag", session.tag).putString("sha", session.sha256)
            .putLong("code", session.code).apply()
        return session
    }

    fun progress(context: Context, session: Session): Progress {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = dm.query(DownloadManager.Query().setFilterById(session.id))
            ?: return Progress(false, 0, "لم يتم العثور على التنزيل.")
        query.use { row ->
            if (!row.moveToFirst()) return Progress(false, 0, "لم يتم العثور على التنزيل.")
            val state = row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val percent = if (total > 0) ((done * 100 / total).coerceIn(0, 100)).toInt() else 0
            return when (state) {
                DownloadManager.STATUS_SUCCESSFUL -> Progress(true, 100)
                DownloadManager.STATUS_FAILED -> Progress(false, percent, "فشل التنزيل؛ حاول مجدداً بعد التأكد من الاتصال.")
                else -> Progress(false, percent)
            }
        }
    }

    suspend fun validateApk(context: Context, session: Session): File = withContext(Dispatchers.IO) {
        val file = targetFile(context, session.tag)
        check(file.isFile && file.length() in 1024..MAX_APK) { "التنزيل ناقص أو تالف." }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buf = ByteArray(65536)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        check(bytesHex(digest.digest()) == session.sha256.lowercase(Locale.US)) {
            "بصمة APK مختلفة عن الإصدار المنشور؛ التثبيت مرفوض."
        }
        val archive = packageInfo(context, file.absolutePath)
        check(archive.packageName == context.packageName &&
            archive.packageName == "com.radwan.raadpharmacy") { "حزمة APK غير مطابقة." }
        check(apkCode(archive) == session.code && session.code > BuildConfig.VERSION_CODE) {
            "رقم الإصدار غير متوافق أو ليس أحدث من المثبت."
        }
        check(certificateDigest(archive) == installedSigner(context)) {
            "مفتاح التوقيع مختلف؛ لن نحذف التطبيق القديم أو بياناته."
        }
        val cache = File(context.cacheDir, "updates").apply { mkdirs() }
        file.copyTo(File(cache, file.name), overwrite = true)
    }

    fun canInstall(context: Context) = Build.VERSION.SDK_INT < 26 ||
        context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"))

    fun install(context: Context, file: File) {
        check(canInstall(context)) { "يرجى السماح بتثبيت التطبيقات من هذا المصدر أولاً." }
        check(file.isFile && file.canonicalPath.startsWith(
            File(context.cacheDir, "updates").canonicalPath + File.separator))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

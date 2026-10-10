package com.radwan.raadpharmacy.backup

import org.json.JSONObject
import java.util.UUID

/** A complete, portable recovery point. No installation-specific key is needed. */
internal object PortableBackup {
    const val FORMAT = "raad-portable-backup-v3"
    const val EXTENSION = ".raadbackup"
    const val FOLDER = "Raad Pharmacy Backups"
    fun encode(capture: BackupCapture, pending: JSONObject, now: Long): ByteArray {
        val document = BackupArchive.snapshot(capture, UUID.randomUUID().toString(), pending, now)
        document.getJSONObject("ledger").put("appVersion", "3.3.19.4")
        val payload = document.toString()
        val bytes = JSONObject().put("format", FORMAT).put("payload", payload)
            .put("sha256", BackupCrypto.hash(payload.toByteArray(Charsets.UTF_8)))
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= BackupCrypto.MAX_FILE_BYTES) { "حجم النسخة أكبر من الحد الآمن." }
        decode(bytes)
        return bytes
    }
    fun decode(bytes: ByteArray): RestoredArchive {
        require(bytes.size <= BackupCrypto.MAX_FILE_BYTES) { "حجم النسخة أكبر من الحد الآمن." }
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        require(envelope.getString("format") == FORMAT) { "صيغة النسخة غير متوافقة." }
        val payload = envelope.getString("payload")
        require(BackupCrypto.hash(payload.toByteArray(Charsets.UTF_8)) == envelope.getString("sha256")) {
            "ملف النسخة تالف أو ناقص. اختر النسخة السابقة."
        }
        return BackupArchive.restore(JSONObject(payload))
    }
    fun isPortable(bytes: ByteArray): Boolean = runCatching {
        JSONObject(String(bytes, Charsets.UTF_8)).optString("format") == FORMAT
    }.getOrDefault(false)
    fun name(now: Long, sequence: Long) = "RaadPharmacy-$now-$sequence$EXTENSION"
}

internal data class AutomaticBackupPlace(
    val id: String, val enabled: Boolean = true, val sequence: Long = -1,
    val updatedAt: Long = 0, val bytes: Long = 0, val location: String = "",
    val error: String? = null
)
internal data class AutomaticBackupState(
    val phone: AutomaticBackupPlace = AutomaticBackupPlace("phone"),
    val sd: AutomaticBackupPlace = AutomaticBackupPlace("sd", enabled = false),
    val drive: AutomaticBackupPlace = AutomaticBackupPlace("drive", enabled = false),
    val account: String? = null, val busy: Boolean = false, val uploading: Boolean = false
)
internal data class PortableBackupItem(val place: String, val locator: String, val name: String,
    val createdAt: Long, val bytes: Long)

internal fun automaticBackupStatus(place: AutomaticBackupPlace, latest: Long): String = when {
    !place.enabled -> "غير مفعّل"
    place.error != null -> place.error
    place.updatedAt == 0L -> "بانتظار النسخة الأولى"
    place.sequence != latest -> "بانتظار تحديث النسخة"
    else -> "محدّث تلقائيًا"
}

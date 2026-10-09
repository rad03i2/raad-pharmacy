package com.radwan.raadpharmacy.backup

import com.radwan.raadpharmacy.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Logical format extends the existing JSON ledger, retaining legacy import support. */
object BackupArchive {
    const val FORMAT = "raad-local-backup-v2"
    fun snapshot(capture: BackupCapture, chain: String, pending: JSONObject, now: Long): JSONObject = JSONObject().apply {
        put("format", FORMAT); put("type", "SNAPSHOT"); put("chain", chain)
        put("sequence", capture.sequence); put("createdAt", now)
        put("ledger", JSONObject(BackupJson.encode(capture.customers.map { it.toModel() },
            capture.entries.map { it.toModel() }, "3.3.19.1", now)))
        put("photos", JSONArray().apply { capture.photos.forEach { photo ->
            put(JSONObject().put("id", photo.customerId).put("bytes", Base64.getEncoder().encodeToString(photo.bytes)))
        } })
        put("pendingCloud", pending); put("restoreHeld", capture.restoreHeld)
    }
    fun changes(rows: List<BackupChangeEntity>, chain: String, pending: JSONObject): JSONObject = JSONObject().apply {
        require(rows.isNotEmpty())
        put("format", FORMAT); put("type", "CHANGES"); put("chain", chain)
        put("from", rows.first().sequence); put("sequence", rows.last().sequence)
        put("createdAt", System.currentTimeMillis()); put("pendingCloud", pending)
        put("events", JSONArray().apply { rows.forEach { r -> put(JSONObject().apply {
            put("sequence", r.sequence); put("eventId", r.eventId); put("kind", r.kind)
            put("id", r.entityId); put("action", r.action); put("origin", r.origin)
            put("occurredAt", r.occurredAt); put("payload", JSONObject(r.payload))
        }) } })
    }
    private fun checkedPhoto(id: String, encoded: String): BackupPhotoEntity {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= com.radwan.raadpharmacy.customer.CustomerPhotoStore.MAX_BACKUP_PHOTO_BYTES) { "صورة النسخة أكبر من الحجم الآمن." }
        return BackupPhotoEntity(id, bytes)
    }
    fun validateHeader(root: JSONObject) {
        require(root.getString("format") == FORMAT) { "صيغة النسخة غير متوافقة." }
        require(root.getString("chain").isNotBlank() && root.getLong("sequence") >= 0)
        require(root.getString("type") in listOf("SNAPSHOT", "CHANGES"))
    }
    fun restore(snapshot: JSONObject, changes: List<JSONObject> = emptyList(), target: Long = snapshot.getLong("sequence")): RestoredArchive {
        validateHeader(snapshot)
        require(snapshot.getString("type") == "SNAPSHOT") { "اختر نسخة كاملة أو مجلدًا يحتوي سلسلة النسخ." }
        val payload = BackupValidator.parseValid(snapshot.getJSONObject("ledger").toString())
        val customers = payload.customers.associateBy { it.id }.toMutableMap()
        val entries = payload.entries.associateBy { it.id }.toMutableMap()
        val photos = mutableMapOf<String, BackupPhotoEntity>()
        val array = snapshot.getJSONArray("photos")
        for (i in 0 until array.length()) {
            val p = array.getJSONObject(i)
            val id = p.getString("id")
            require(id.matches(Regex("[a-zA-Z0-9_-]+")) && id in customers && id !in photos)
            photos[id] = checkedPhoto(id, p.getString("bytes"))
        }
        val chain = snapshot.getString("chain")
        var cursor = snapshot.getLong("sequence")
        require(target >= cursor)
        val events = java.util.TreeMap<Long, JSONObject>()
        var pending = snapshot.optJSONObject("pendingCloud") ?: JSONObject()
        var date = payload.createdAt
        changes.forEach { part ->
            validateHeader(part)
            require(part.getString("chain") == chain && part.getString("type") == "CHANGES")
            val rows = part.getJSONArray("events")
            require(rows.length() > 0)
            require(part.getLong("sequence") - part.getLong("from") + 1 == rows.length().toLong())
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val seq = row.getLong("sequence")
                require(seq == part.getLong("from") + i)
                val previous = events.put(seq, row)
                require(previous == null || previous.toString() == row.toString()) { "تغييرات متعارضة في سلسلة النسخ." }
            }
            if (part.getLong("sequence") in (cursor + 1)..target) {
                pending = part.optJSONObject("pendingCloud") ?: pending
            }
        }
        val ids = hashSetOf<String>()
        while (cursor < target) {
            val row = events[++cursor] ?: error("سلسلة النسخ غير مكتملة عند التغيير $cursor.")
            require(ids.add(row.getString("eventId"))) { "معرف تغيير مكرر." }
            val id = row.getString("id"); val kind = row.getString("kind"); val action = row.getString("action")
            require(action in listOf("UPSERT", "DELETE"))
            when (kind) {
                "CUSTOMER" -> if (action == "DELETE") {
                    customers.remove(id); entries.entries.removeAll { it.value.customerId == id }; photos.remove(id)
                } else {
                    val record = BackupJson.parse(row.getJSONObject("payload").toString()).customers.single()
                    require(record.id == id); customers[id] = record
                }
                "ENTRY" -> if (action == "DELETE") entries.remove(id) else {
                    val record = BackupJson.parse(row.getJSONObject("payload").toString()).entries.single()
                    require(record.id == id && record.customerId in customers); entries[id] = record
                }
                "PHOTO" -> if (action == "DELETE") photos.remove(id) else {
                    require(id.matches(Regex("[a-zA-Z0-9_-]+")) && id in customers)
                    photos[id] = checkedPhoto(id, row.getJSONObject("payload").getString("bytes"))
                }
                else -> error("نوع تغيير غير معروف.")
            }
            date = maxOf(date, row.getLong("occurredAt"))
        }
        val restored = BackupPayload(customers.values.toList(), entries.values.toList(), date, 1)
        BackupValidator.validate(restored)
        return RestoredArchive(restored, photos.values.toList(), pending, cursor)
    }
}
data class RestoredArchive(val ledger: BackupPayload, val photos: List<BackupPhotoEntity>, val pendingCloud: JSONObject, val sequence: Long)

package com.radwan.raadpharmacy.data

import org.json.JSONArray
import org.json.JSONObject

object BackupJson {
    const val FORMAT = "raad-pharmacy-backup"
    private const val LEGACY_FORMAT = "abosmra-backup"
    const val SCHEMA_VERSION = 1

    fun encode(
        customers: List<Customer>,
        entries: List<LedgerEntry>,
        appVersion: String,
        createdAt: Long = System.currentTimeMillis()
    ): String {
        return JSONObject().apply {
            put("backupFormat", FORMAT)
            put("schemaVersion", SCHEMA_VERSION)
            put("app", "دفتر صيدلية رعد")
            put("appVersion", appVersion)
            put("createdAt", createdAt)
            put("storage", "room-sqlite")
            put("settings", JSONObject().apply {
                put("currency", "IQD")
                put("englishDigits", true)
                put("rtl", true)
            })
            put("customers", JSONArray().apply {
                customers.forEach { customer ->
                    put(JSONObject().apply {
                        put("id", customer.id)
                        put("name", customer.name)
                        put("phone", customer.phone ?: "")
                        put("area", customer.area)
                        put("address", customer.address)
                        put("openingDebt", customer.openingDebt)
                        put("notes", customer.notes)
                        put("createdAt", customer.createdAt)
                    })
                }
            })
            put("entries", JSONArray().apply {
                entries.forEach { entry ->
                    put(JSONObject().apply {
                        put("id", entry.id)
                        put("customerId", entry.customerId)
                        put("type", entry.type.name)
                        put("amount", entry.amount)
                        put("bottles", entry.bottles ?: JSONObject.NULL)
                        put("bottlePrice", entry.bottlePrice ?: JSONObject.NULL)
                        put("details", entry.details)
                        put("createdAt", entry.createdAt)
                    })
                }
            })
        }.toString(2)
    }

    fun parse(raw: String): BackupPayload {
        require(raw.isNotBlank()) { "ملف النسخة الاحتياطية فارغ." }
        val root = JSONObject(raw)
        val format = root.optString("backupFormat")
        val modern = format == FORMAT
        val legacyFormat = format == LEGACY_FORMAT
        val legacy = format.isBlank() && root.has("customers") && root.has("entries")
        require(modern || legacyFormat || legacy) {
            "هذا الملف ليس نسخة احتياطية معروفة لدفتر صيدلية رعد."
        }

        val schema = if (modern || legacyFormat) root.optInt("schemaVersion", 0) else 0
        if (modern || legacyFormat) {
            require(schema in 1..SCHEMA_VERSION) {
                "إصدار النسخة الاحتياطية أحدث من إصدار التطبيق الحالي."
            }
        }

        val customersArray = root.optJSONArray("customers")
            ?: error("قسم الزبائن غير موجود في النسخة.")
        val entriesArray = root.optJSONArray("entries")
            ?: error("قسم الحركات غير موجود في النسخة.")

        val customers = buildList {
            for (index in 0 until customersArray.length()) {
                val item = customersArray.getJSONObject(index)
                add(Customer(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    phone = item.optString("phone").takeIf { it.isNotBlank() },
                    area = item.optString("area"),
                    address = item.optString("address"),
                    openingDebt = item.optLong("openingDebt"),
                    notes = item.optString("notes"),
                    createdAt = item.optLong("createdAt")
                ))
            }
        }

        val entries = buildList {
            for (index in 0 until entriesArray.length()) {
                val item = entriesArray.getJSONObject(index)
                val type = runCatching {
                    EntryType.valueOf(item.getString("type"))
                }.getOrElse {
                    error("نوع حركة غير معروف في النسخة الاحتياطية.")
                }
                add(LedgerEntry(
                    id = item.getString("id"),
                    customerId = item.getString("customerId"),
                    type = type,
                    amount = item.getLong("amount"),
                    bottles = if (item.isNull("bottles")) null else item.getInt("bottles"),
                    bottlePrice = if (item.isNull("bottlePrice")) null else item.getLong("bottlePrice"),
                    details = item.optString("details"),
                    createdAt = item.getLong("createdAt")
                ))
            }
        }

        return BackupPayload(
            customers = customers,
            entries = entries,
            createdAt = root.optLong(
                if (modern) "createdAt" else "exportedAt",
                System.currentTimeMillis()
            ),
            schemaVersion = schema
        )
    }
}

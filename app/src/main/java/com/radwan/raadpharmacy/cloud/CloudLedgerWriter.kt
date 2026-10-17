package com.radwan.raadpharmacy.cloud

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.*
import java.time.OffsetDateTime

internal class CloudLedgerWriteConflict : IllegalStateException("تعذر تأكيد السجل؛ هويته أو حالته على الخادم تحتاج مراجعة. بقي الطلب محفوظًا.")

/** Creation metadata belongs to INSERT only. Never replay a millisecond local
 * timestamp over an immutable, microsecond server created_at during an edit. */
internal class CloudLedgerWriter(private val client: SupabaseClient, private val pharmacy: String) {
    private val owner = requireCloudSyncOwner(client.auth.currentSessionOrNull()?.user?.id)
    private fun guard() { check(client.auth.currentSessionOrNull()?.user?.id == owner) { "تغير حساب المزامنة؛ الطلب محفوظ." } }
    // Older Android Instant parsers accept only Z; PostgREST also returns +00:00.
    private fun time(value: String) = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()

    private suspend fun <T> persist(createFirst: Boolean, patch: suspend () -> List<T>,
        insert: suspend () -> List<T>, read: suspend () -> List<T>, verify: (T) -> Boolean) {
        guard()
        if (!createFirst) {
            val updated = patch().singleOrNull()
            guard()
            if (updated != null) {
                if (!verify(updated)) throw CloudLedgerWriteConflict()
                return
            }
        }
        val saved = try { insert().singleOrNull() }
        catch (error: RestException) {
            if (error.statusCode != 409) throw error
            guard()
            // Bootstrap must not overwrite a row another device just created.
            // An ordinary pending edit may retry its mutable patch, never identity fields.
            (if (createFirst) read() else patch()).singleOrNull()
        }
        guard()
        if (saved == null || !verify(saved)) throw CloudLedgerWriteConflict()
    }

    suspend fun save(value: CloudCustomerWrite, createFirst: Boolean = false) {
        require(value.pharmacyId == pharmacy)
        val patch = buildJsonObject {
            put("name", value.name); put("phone", value.phone?.let(::JsonPrimitive) ?: JsonNull)
            put("area", value.area); put("address", value.address?.let(::JsonPrimitive) ?: JsonNull)
            put("opening_debt", value.openingDebt); put("notes", value.notes?.let(::JsonPrimitive) ?: JsonNull)
        }
        persist(createFirst, patch = {
            guard()
            client.from("customers").update(patch) {
                select(); filter { eq("id", value.id); eq("pharmacy_id", pharmacy); exact("deleted_at", null) }
            }.decodeList<CloudCustomerRow>()
        }, insert = {
            guard(); client.from("customers").insert(value) { select() }.decodeList<CloudCustomerRow>()
        }, read = {
            guard(); client.from("customers").select {
                filter { eq("id", value.id); eq("pharmacy_id", pharmacy) }
            }.decodeList<CloudCustomerRow>()
        }) { row ->
            row.id == value.id && row.pharmacyId == pharmacy && row.deletedAt == null &&
                row.name == value.name && row.phone.orEmpty() == value.phone.orEmpty() && row.area == value.area &&
                row.address.orEmpty() == value.address.orEmpty() && row.openingDebt == value.openingDebt &&
                row.notes.orEmpty() == value.notes.orEmpty()
        }
    }

    suspend fun save(value: CloudTransactionWrite, createFirst: Boolean = false) {
        require(value.pharmacyId == pharmacy)
        val patch = buildJsonObject {
            put("type", value.type); put("amount", value.amount)
            put("notes", value.notes?.let(::JsonPrimitive) ?: JsonNull)
            put("device_id", value.deviceId?.let(::JsonPrimitive) ?: JsonNull)
            put("occurred_at", value.occurredAt)
        }
        persist(createFirst, patch = {
            guard()
            client.from("transactions").update(patch) {
                select(); filter {
                    eq("id", value.id); eq("pharmacy_id", pharmacy); eq("customer_id", value.customerId)
                    exact("deleted_at", null)
                }
            }.decodeList<CloudTransactionRow>()
        }, insert = {
            guard(); client.from("transactions").insert(value) { select() }.decodeList<CloudTransactionRow>()
        }, read = {
            guard(); client.from("transactions").select {
                filter { eq("id", value.id); eq("pharmacy_id", pharmacy) }
            }.decodeList<CloudTransactionRow>()
        }) { row ->
            row.id == value.id && row.pharmacyId == pharmacy && row.customerId == value.customerId && row.deletedAt == null &&
                row.type == value.type && row.amount.isFinite() && row.amount == value.amount.toDouble() &&
                row.amount.toLong() == value.amount && row.notes.orEmpty() == value.notes.orEmpty() &&
                time(row.occurredAt) == time(value.occurredAt) && time(value.occurredAt) != null
        }
    }
}

package com.radwan.raadpharmacy.backup

import com.radwan.raadpharmacy.data.*
import org.json.JSONObject
import java.util.UUID

internal data class RecoveryTotals(val customers: Int, val debts: Long, val payments: Long,
    val debtCount: Int, val paymentCount: Int) {
    companion object {
        fun of(customers: List<CustomerEntity>, entries: List<LedgerEntryEntity>): RecoveryTotals {
            fun total(values: List<Long>) = values.fold(0L) { a, b -> Math.addExact(a, b) }
            return RecoveryTotals(customers.size,
                total(customers.map { it.openingDebt } + entries.filter { it.type == "DEBT" }.map { it.amount }),
                total(entries.filter { it.type == "PAYMENT" }.map { it.amount }),
                entries.count { it.type == "DEBT" }, entries.count { it.type == "PAYMENT" })
        }
    }
}

internal data class RecoveryRecord(val kind: String, val id: String, val payload: String,
    val state: String = "PENDING", val reason: String = "", val added: Boolean = false, val pharmacy: String = "") {
    fun json(owner: String): String = JSONObject().put("owner", owner).put("kind", kind).put("id", id)
        .put("payload", payload).put("state", state).put("reason", reason).put("added", added).put("pharmacy", pharmacy).toString()
    companion object {
        fun parse(value: String): RecoveryRecord {
            val o = JSONObject(value)
            return RecoveryRecord(o.getString("kind"), o.getString("id"), o.getString("payload"),
                o.getString("state"), o.optString("reason"), o.optBoolean("added"), o.optString("pharmacy"))
        }
        fun uuid(id: String): Boolean = runCatching { UUID.fromString(id).toString().equals(id, true) }.getOrDefault(false)
    }
}

/** Stable identifiers establish identity. Totals and names alone never authorize a financial write. */
internal data class RecoveryPlan(val sequence: Long, val current: RecoveryTotals, val incoming: RecoveryTotals,
    val customers: List<CustomerEntity>, val entries: List<LedgerEntryEntity>,
    val records: List<RecoveryRecord>, val existing: Int) {
    val conflicts get() = records.count { it.state == "CONFLICT" }
    val additions get() = customers.size + entries.size
    val checking get() = records.count { it.state == "PENDING" }
    companion object {
        fun build(current: BackupCapture, archive: RestoredArchive, deleted: Set<Pair<String, String>> = emptySet()): RecoveryPlan {
            BackupValidator.validate(archive.ledger)
            val originalCustomers = current.customers.associateBy { it.id }
            val originalEntries = current.entries.associateBy { it.id }
            val incomingCustomers = archive.ledger.customers.map { it.toEntity() }
            val incomingEntries = archive.ledger.entries.map { it.toEntity() }
            val addedCustomers = mutableListOf<CustomerEntity>()
            val addedEntries = mutableListOf<LedgerEntryEntity>()
            val records = mutableListOf<RecoveryRecord>()
            val blocked = hashSetOf<String>()
            var identical = 0
            fun customerPayload(c: CustomerEntity) = BackupJson.encode(listOf(c.toModel()), emptyList(), "3.3.22.21")
            fun entryPayload(e: LedgerEntryEntity) = BackupJson.encode(emptyList(), listOf(e.toModel()), "3.3.22.21")
            val names = current.customers.groupBy { normalizeCustomerName(it.name) }
            val phones = current.customers.filter { !it.phone.isNullOrBlank() }.groupBy { normalizeCustomerPhoneForDuplicate(it.phone.orEmpty()) }
            val incomingNames = incomingCustomers.groupBy { normalizeCustomerName(it.name) }
            val incomingPhones = incomingCustomers.filter { !it.phone.isNullOrBlank() }.groupBy { normalizeCustomerPhoneForDuplicate(it.phone.orEmpty()) }
            for (c in incomingCustomers) {
                val old = originalCustomers[c.id]
                val reason = when {
                    ("CUSTOMER" to c.id) in deleted -> "للزبون طلب حذف محلي معلق؛ لم تُعد إضافته."
                    !RecoveryRecord.uuid(c.id) -> "معرّف قديم لا يمكن مطابقته بأمان مع معرّف الخادم."
                    old != null && old != c -> "المعرّف موجود ببيانات مختلفة؛ الرصيد الافتتاحي الحالي " +
                        old.openingDebt + " والنسخة " + c.openingDebt + ". احتُفظ بالحالي."
                    old == null && (names[normalizeCustomerName(c.name)].orEmpty().isNotEmpty() ||
                        (c.phone?.isNotBlank() == true && phones[normalizeCustomerPhoneForDuplicate(c.phone.orEmpty())].orEmpty().isNotEmpty())) ->
                        "يوجد زبون باسم أو رقم هاتف مماثل بمعرّف آخر؛ يلزم التحقق من الهوية."
                    old == null && (incomingNames[normalizeCustomerName(c.name)].orEmpty().size > 1 ||
                        (c.phone?.isNotBlank() == true && incomingPhones[normalizeCustomerPhoneForDuplicate(c.phone.orEmpty())].orEmpty().size > 1)) ->
                        "توجد هويات زبائن مماثلة داخل النسخة؛ لم تُدمج تلقائيًا."
                    else -> ""
                }
                if (reason.isNotEmpty() && (old == null || old.openingDebt != c.openingDebt)) blocked += c.id
                if (reason.isEmpty()) { if (old == null) addedCustomers += c else identical++ }
                records += RecoveryRecord("CUSTOMER", c.id, customerPayload(c),
                    if (reason.isEmpty()) "PENDING" else "CONFLICT", reason, old == null && reason.isEmpty())
            }
            // Detect aliases even when the two IDs differ; never infer a new operation from the balance difference.
            fun fingerprint(e: LedgerEntryEntity) = listOf(e.customerId, e.type, e.amount, e.createdAt, e.details)
            val fingerprints = current.entries.groupBy(::fingerprint)
            val incomingFingerprints = incomingEntries.groupBy(::fingerprint)
            for (e in incomingEntries) {
                val old = originalEntries[e.id]
                val reason = when {
                    ("ENTRY" to e.id) in deleted -> "للحركة طلب حذف محلي معلق؛ لم تُعد إضافتها."
                    !RecoveryRecord.uuid(e.id) || !RecoveryRecord.uuid(e.customerId) -> "هوية الحركة القديمة غير كافية للمزامنة الآمنة."
                    e.amount > 9_007_199_254_740_991L -> "قيمة الحركة تتجاوز حد المطابقة العددية الآمنة مع الخادم."
                    e.customerId in blocked -> "هوية الزبون أو رصيده الافتتاحي متعارضان."
                    old != null && old != e -> "الحركة موجودة بالمُعرّف نفسه وبمبلغ أو تفاصيل أو تاريخ مختلف؛ الحالي " +
                        old.type + " " + old.amount + " والنسخة " + e.type + " " + e.amount + ". لم تُستبدل."
                    old == null && fingerprints[fingerprint(e)].orEmpty().isNotEmpty() ->
                        "توجد حركة مماثلة بمعرّف مختلف؛ لم تُضف لتجنب التكرار."
                    old == null && incomingFingerprints[fingerprint(e)].orEmpty().size > 1 ->
                        "النسخة تحتوي حركات مماثلة بمعرّفات مختلفة؛ يلزم التحقق قبل دمجها."
                    else -> ""
                }
                if (reason.isEmpty()) { if (old == null) addedEntries += e else identical++ }
                records += RecoveryRecord("ENTRY", e.id, entryPayload(e),
                    if (reason.isEmpty()) "PENDING" else "CONFLICT", reason, old == null && reason.isEmpty())
            }
            // A union can be invalid even if each backup is individually valid (e.g. overlapping repayments).
            val mergedCustomers = (current.customers + addedCustomers).associateBy { it.id }
            val mergedEntries = current.entries + addedEntries
            val mergedByCustomer = mergedEntries.groupBy { it.customerId }
            val invalid = addedEntries.map { it.customerId }.toSet().filter { id ->
                !LedgerRules.isChronologicallyValid(mergedCustomers.getValue(id).toModel(), mergedByCustomer[id].orEmpty().map { it.toModel() })
            }.toSet()
            if (invalid.isNotEmpty()) {
                val rejected = addedEntries.filter { it.customerId in invalid }.mapTo(hashSetOf()) { it.id }
                addedEntries.removeAll { it.id in rejected }
                for (i in records.indices) if (records[i].kind == "ENTRY" && records[i].id in rejected)
                    records[i] = records[i].copy(state = "CONFLICT", reason = "دمج الحركة يجعل التسديد أكبر من الرصيد المتاح.", added = false)
            }
            return RecoveryPlan(current.sequence, RecoveryTotals.of(current.customers, current.entries),
                RecoveryTotals.of(incomingCustomers, incomingEntries), addedCustomers, addedEntries, records, identical)
        }
    }
}

internal object RecoveryKeys {
    const val SESSION = "restore21:session"
    const val ITEM = "restore21:item:"
    const val GUARD = "restore21:guard:"
    fun item(owner: String, record: RecoveryRecord) = ITEM + owner + ":" + record.kind + ":" + record.id
    fun guard(kind: String, id: String) = GUARD + kind + ":" + id
}

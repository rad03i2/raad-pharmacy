package com.radwan.raadpharmacy.backup

import android.content.Context
import androidx.work.*
import com.radwan.raadpharmacy.cloud.*
import com.radwan.raadpharmacy.data.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.Instant

internal interface RecoveryRemote {
    suspend fun customers(): List<CloudCustomerRow>
    suspend fun entries(): List<CloudTransactionRow>
    suspend fun entriesForCustomer(id: String): List<CloudTransactionRow> = entries().filter { it.customerId == id }
    suspend fun customer(id: String): CloudCustomerRow?
    suspend fun entry(id: String): CloudTransactionRow?
    suspend fun insert(customer: CustomerEntity)
    suspend fun insert(entry: LedgerEntryEntity)
}

/** INSERT only. A concurrent conflict is reread; this path never overwrites a financial server row. */
internal class SupabaseRecoveryRemote(private val client: SupabaseClient, private val pharmacy: String,
    private val device: String, private val owner: String) : RecoveryRemote {
    private fun guard() { check(client.auth.currentSessionOrNull()?.user?.id == owner) { "تغير الحساب؛ لم تُرسل البيانات المستردة." } }
    override suspend fun customers(): List<CloudCustomerRow> {
        val all = mutableListOf<CloudCustomerRow>()
        do {
            guard(); val after = all.lastOrNull()?.id
            val page = client.from("customers").select {
                filter { eq("pharmacy_id", pharmacy); if (after != null) gt("id", after) }
                order("id", Order.ASCENDING); range(0L..199L)
            }.decodeList<CloudCustomerRow>()
            all.addAll(page)
        } while (page.size == 200)
        guard(); return all
    }
    override suspend fun entries(): List<CloudTransactionRow> {
        val all = mutableListOf<CloudTransactionRow>()
        do {
            guard(); val after = all.lastOrNull()?.id
            val page = client.from("transactions").select {
                filter { eq("pharmacy_id", pharmacy); if (after != null) gt("id", after) }
                order("id", Order.ASCENDING); range(0L..199L)
            }.decodeList<CloudTransactionRow>()
            all.addAll(page)
        } while (page.size == 200)
        guard(); return all
    }
    override suspend fun customer(id: String): CloudCustomerRow? {
        guard()
        return client.from("customers").select { filter { eq("id", id); eq("pharmacy_id", pharmacy) } }
            .decodeList<CloudCustomerRow>().singleOrNull().also { guard() }
    }
    override suspend fun entry(id: String): CloudTransactionRow? {
        guard()
        return client.from("transactions").select { filter { eq("id", id); eq("pharmacy_id", pharmacy) } }
            .decodeList<CloudTransactionRow>().singleOrNull().also { guard() }
    }
    override suspend fun entriesForCustomer(id: String): List<CloudTransactionRow> {
        val all = mutableListOf<CloudTransactionRow>()
        do {
            guard(); val after = all.lastOrNull()?.id
            val page = client.from("transactions").select {
                filter { eq("pharmacy_id", pharmacy); eq("customer_id", id); if (after != null) gt("id", after) }
                order("id", Order.ASCENDING); range(0L..199L)
            }.decodeList<CloudTransactionRow>()
            all.addAll(page)
        } while (page.size == 200)
        guard(); return all
    }
    override suspend fun insert(customer: CustomerEntity) {
        guard()
        client.from("customers").insert(CloudCustomerWrite(customer.id, pharmacy, customer.name, customer.phone,
            customer.area, customer.address.takeIf(String::isNotBlank), customer.openingDebt,
            customer.notes.takeIf(String::isNotBlank), customer.createdByName,
            createdAt = Instant.ofEpochMilli(customer.createdAt).toString()))
        guard()
    }
    override suspend fun insert(entry: LedgerEntryEntity) {
        guard()
        client.from("transactions").insert(CloudTransactionWrite(entry.id, pharmacy, entry.customerId, entry.id,
            entry.type, entry.amount, entry.details.takeIf(String::isNotBlank), device,
            Instant.ofEpochMilli(entry.createdAt).toString(), Instant.ofEpochMilli(entry.createdAt).toString()))
        guard()
    }
}

internal class RecoveryCloudVerifier(private val dao: PharmacyLedgerDao, private val remote: RecoveryRemote) {
    private fun time(value: String) = runCatching { Instant.parse(value).toEpochMilli() }
        .recoverCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
    private fun validRemoteBalance(parent: CloudCustomerRow, candidate: LedgerEntryEntity,
        rows: Collection<CloudTransactionRow>): Boolean = runCatching {
        val entries = rows.filter { it.customerId == parent.id && it.deletedAt == null }.map {
            require(it.amount.isFinite() && it.amount > 0 && it.amount <= 9_007_199_254_740_991.0 && it.amount.toLong().toDouble() == it.amount)
            LedgerEntry(it.id, it.customerId, EntryType.valueOf(it.type), it.amount.toLong(),
                details = it.notes.orEmpty(), createdAt = checkNotNull(time(it.occurredAt)))
        } + candidate.toModel()
        LedgerRules.isChronologicallyValid(Customer(parent.id, parent.name, openingDebt = parent.openingDebt,
            createdAt = checkNotNull(time(parent.createdAt))), entries)
    }.getOrDefault(false)
    private fun same(local: CustomerEntity, row: CloudCustomerRow): Boolean = row.deletedAt == null &&
        row.id == local.id && row.name == local.name && row.phone.orEmpty() == local.phone.orEmpty() &&
        row.openingDebt == local.openingDebt && row.area == local.area && row.address.orEmpty() == local.address &&
        row.notes.orEmpty() == local.notes && time(row.createdAt) == local.createdAt
    private fun same(local: LedgerEntryEntity, row: CloudTransactionRow): Boolean = row.deletedAt == null &&
        row.id == local.id && row.customerId == local.customerId && row.type == local.type &&
        row.amount.isFinite() && row.amount == local.amount.toDouble() && row.amount.toLong() == local.amount &&
        row.notes.orEmpty() == local.details && time(row.occurredAt) == local.createdAt

    suspend fun run(owner: String, pharmacy: String = "") {
        val controls = dao.recoveryControls().filter { it.id.startsWith(RecoveryKeys.ITEM) }
            .filter { JSONObject(it.value).optString("owner") == owner && RecoveryRecord.parse(it.value).state == "PENDING" }
            .sortedWith(compareBy<BackupControlEntity> { if (RecoveryRecord.parse(it.value).kind == "CUSTOMER") 0 else 1 }
                .thenBy { val r = RecoveryRecord.parse(it.value)
                    if (r.kind == "ENTRY") BackupJson.parse(r.payload).entries.single().createdAt else 0L }
                .thenBy { val r = RecoveryRecord.parse(it.value)
                    if (r.kind == "ENTRY" && BackupJson.parse(r.payload).entries.single().type == EntryType.PAYMENT) 1 else 0 })
        if (controls.isEmpty()) return
        val customers = remote.customers().associateBy { it.id }.toMutableMap()
        val entries = remote.entries().associateBy { it.id }.toMutableMap()
        for (control in controls) {
            val record = RecoveryRecord.parse(control.value)
            var conflict = ""
            try {
                if (record.pharmacy != pharmacy) conflict = "تختلف صيدلية الاسترداد عن الحساب الحالي؛ لم تُرسل البيانات."
                else if (record.kind == "CUSTOMER") {
                    val c = BackupJson.parse(record.payload).customers.single().toEntity()
                    val row = remote.customer(c.id)
                    val previouslyExisted = c.id in customers
                    if (row != null) customers[c.id] = row
                    if (dao.getCustomerById(c.id) != c) conflict = "تغير الزبون محليًا بعد المقارنة؛ يلزم إعادة المراجعة."
                    else if (row == null && previouslyExisted) conflict = "اختفى الزبون من الخادم أثناء الفحص؛ لم يُعد إنشاؤه."
                    else if (row != null && !same(c, row)) conflict = "الخادم يحتوي بيانات مختلفة أو سجلًا محذوفًا؛ لم يُستبدل."
                    else if (row == null) {
                        if (customers.values.any { normalizeCustomerName(it.name) == normalizeCustomerName(c.name) ||
                                (c.phone?.isNotBlank() == true && normalizeCustomerPhoneForDuplicate(it.phone.orEmpty()) == normalizeCustomerPhoneForDuplicate(c.phone.orEmpty())) })
                            conflict = "اسم الزبون أو هاتفه موجود على الخادم بمعرّف آخر."
                        else {
                            try { remote.insert(c) } catch (e: RestException) { if (e.statusCode != 409) throw e }
                            val confirmed = remote.customer(c.id) ?: error("لم يتأكد حفظ الزبون على الخادم.")
                            if (!same(c, confirmed)) conflict = "ظهر تعارض أثناء حفظ الزبون؛ لم يُستبدل."
                            else customers[c.id] = confirmed
                        }
                    }
                } else {
                    val e = BackupJson.parse(record.payload).entries.single().toEntity()
                    val previouslyExisted = e.id in entries
                    var row = remote.entry(e.id)
                    if (row == null && !previouslyExisted) {
                        val latest = remote.entriesForCustomer(e.customerId)
                        entries.entries.removeAll { it.value.customerId == e.customerId }
                        latest.forEach { entries[it.id] = it }
                        row = latest.singleOrNull { it.id == e.id }
                    }
                    if (row != null) entries[e.id] = row
                    val parent = dao.getCustomerById(e.customerId)
                    val remoteParent = remote.customer(e.customerId)
                    if (dao.getEntryById(e.id) != e) conflict = "تغيرت الحركة محليًا بعد المقارنة؛ لم تُرسل."
                    else if (row == null && previouslyExisted) conflict = "اختفت الحركة أثناء الفحص؛ لم تُعد إنشاؤها."
                    else if (row != null && !same(e, row)) conflict = "الحركة على الخادم مختلفة أو محذوفة؛ مبلغ النسخة " +
                        e.amount + " ومبلغ الخادم " + row.amount + ". لم تُستبدل."
                    else if (row == null) {
                        if (parent == null || remoteParent == null || remoteParent.deletedAt != null ||
                            parent.openingDebt != remoteParent.openingDebt) conflict = "الزبون أو الرصيد الافتتاحي لم يتطابق مع الخادم."
                        else if (entries.values.any { it.operationId == e.id ||
                            (it.customerId == e.customerId && it.type == e.type && it.amount == e.amount.toDouble() &&
                                time(it.occurredAt) == e.createdAt && it.notes.orEmpty() == e.details) })
                            conflict = "عملية مماثلة موجودة بمعرّف آخر؛ لم تُنشأ نسخة ثانية."
                        else if (!validRemoteBalance(remoteParent, e, entries.values)) {
                            if (dao.pendingCloudEntriesForCustomer(e.customerId) > 0)
                                throw java.io.IOException("Current ordinary operations are still pending")
                            conflict = "تسلسل الخادم أو رصيده لا يسمح بدمج الحركة بأمان؛ لم تُرفع."
                        }
                        else {
                            try { remote.insert(e) } catch (ex: RestException) { if (ex.statusCode != 409) throw ex }
                            val confirmed = remote.entry(e.id) ?: error("لم يتأكد حفظ الحركة على الخادم.")
                            if (!same(e, confirmed)) conflict = "ظهر تعارض أثناء الحفظ؛ لم تُستبدل الحركة."
                            else entries[e.id] = confirmed
                        }
                    }
                }
                val done = record.copy(state = if (conflict.isEmpty()) "VERIFIED" else "CONFLICT",
                    reason = if (conflict.isEmpty()) "تأكد وجود السجل ومطابقته على الخادم." else conflict)
                dao.finishRecovery(control.id, control.value, done.json(owner),
                    if (conflict.isEmpty()) RecoveryKeys.guard(record.kind, record.id) else null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                dao.finishRecovery(control.id, control.value,
                    record.copy(reason = "تعذر التحقق الآن؛ السجل محفوظ وستعاد المحاولة.").json(owner), null)
                throw error
            }
        }
    }
}

class RecoverySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val dao = PharmacyLedgerDatabase.get(applicationContext).dao()
        val controls = dao.recoveryControls()
        if (controls.none { it.id.startsWith("restore21:refresh:") ||
                (it.id.startsWith(RecoveryKeys.ITEM) && RecoveryRecord.parse(it.value).state == "PENDING") }) Result.success()
        else {
            val client = SupabaseProvider.client
            client.auth.awaitInitialization()
            val owner = checkNotNull(client.auth.currentSessionOrNull()?.user?.id)
            val pendingOwners = dao.recoveryControls().filter { it.id.startsWith(RecoveryKeys.ITEM) && RecoveryRecord.parse(it.value).state == "PENDING" }
                .map { JSONObject(it.value).getString("owner") }.toSet()
            val refresh = "restore21:refresh:" + owner
            if (owner !in pendingOwners && dao.recoveryValue(refresh) == null) Result.retry() else {
                CloudSyncEngine.withLedgerSyncLock {
                    val profile = client.from("profiles").select { filter { eq("id", owner) } }.decodeSingle<CloudProfileRow>()
                    val device = CloudDeviceRegistration.register(applicationContext, profile, owner)
                    RecoveryCloudVerifier(dao, SupabaseRecoveryRemote(client, profile.pharmacyId,
                        device, owner)).run(owner, profile.pharmacyId)
                }
                if (dao.recoveryValue(refresh) != null) {
                    // Protected deltas may have been intentionally skipped. A durable full
                    // catch-up closes that gap even after death immediately after acknowledgement.
                    check(client.auth.currentSessionOrNull()?.user?.id == owner)
                    CloudSyncEngine(applicationContext).pullRemoteNow(forceSnapshot = true)
                    check(client.auth.currentSessionOrNull()?.user?.id == owner)
                    dao.removeRecoveryControl(refresh)
                }
                Result.success()
            }
        }
    } catch (error: Exception) { error.rethrowCloudCancellation(); Result.retry() }
}

internal object RecoveryScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun resume(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                if (PharmacyLedgerDatabase.get(app).dao().recoveryControls().any {
                        it.id.startsWith("restore21:refresh:") ||
                            (it.id.startsWith(RecoveryKeys.ITEM) && RecoveryRecord.parse(it.value).state == "PENDING") }) enqueue(app)
            } catch (error: Exception) {
                error.rethrowCloudCancellation()
                android.util.Log.w("RaadRecovery", "Pending recovery remains durable for the next start")
            }
        }
    }
    fun enqueue(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork("raad-restore21-verify",
            ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<RecoverySyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}

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
    private fun time(value: String) = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
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
            .sortedBy { if (RecoveryRecord.parse(it.value).kind == "CUSTOMER") 0 else 1 }
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
                    val row = customers[c.id]
                    if (dao.getCustomerById(c.id) != c) conflict = "تغير الزبون محليًا بعد المقارنة؛ يلزم إعادة المراجعة."
                    else if (row != null && !same(c, row)) conflict = "الخادم يحتوي بيانات مختلفة أو سجلًا محذوفًا؛ لم يُستبدل."
                    else if (row == null) {
                        if (customers.values.any { it.name.trim().equals(c.name.trim(), true) }) conflict = "اسم الزبون موجود على الخادم بمعرّف آخر."
                        else {
                            try { remote.insert(c) } catch (e: RestException) { if (e.statusCode != 409) throw e }
                            val confirmed = remote.customer(c.id) ?: error("لم يتأكد حفظ الزبون على الخادم.")
                            if (!same(c, confirmed)) conflict = "ظهر تعارض أثناء حفظ الزبون؛ لم يُستبدل."
                            else customers[c.id] = confirmed
                        }
                    }
                } else {
                    val e = BackupJson.parse(record.payload).entries.single().toEntity()
                    val row = entries[e.id]
                    val parent = dao.getCustomerById(e.customerId)
                    val remoteParent = customers[e.customerId]
                    if (dao.getEntryById(e.id) != e) conflict = "تغيرت الحركة محليًا بعد المقارنة؛ لم تُرسل."
                    else if (row != null && !same(e, row)) conflict = "الحركة على الخادم مختلفة أو محذوفة؛ لم تُستبدل."
                    else if (row == null) {
                        if (parent == null || remoteParent == null || remoteParent.deletedAt != null ||
                            parent.openingDebt != remoteParent.openingDebt) conflict = "الزبون أو الرصيد الافتتاحي لم يتطابق مع الخادم."
                        else if (entries.values.any { it.operationId == e.id ||
                            (it.customerId == e.customerId && it.type == e.type && it.amount == e.amount.toDouble() &&
                                time(it.occurredAt) == e.createdAt && it.notes.orEmpty() == e.details) })
                            conflict = "عملية مماثلة موجودة بمعرّف آخر؛ لم تُنشأ نسخة ثانية."
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
        if (dao.recoveryControls().none { it.id.startsWith(RecoveryKeys.ITEM) && RecoveryRecord.parse(it.value).state == "PENDING" }) Result.success()
        else {
            val client = SupabaseProvider.client
            client.auth.awaitInitialization()
            val owner = checkNotNull(client.auth.currentSessionOrNull()?.user?.id)
            val pendingOwners = dao.recoveryControls().filter { it.id.startsWith(RecoveryKeys.ITEM) && RecoveryRecord.parse(it.value).state == "PENDING" }
                .map { JSONObject(it.value).getString("owner") }.toSet()
            if (owner !in pendingOwners) Result.retry() else {
                CloudSyncEngine.withLedgerSyncLock {
                    val profile = client.from("profiles").select { filter { eq("id", owner) } }.decodeSingle<CloudProfileRow>()
                    val device = CloudDeviceRegistration.register(applicationContext, profile, owner)
                    RecoveryCloudVerifier(dao, SupabaseRecoveryRemote(client, profile.pharmacyId,
                        device, owner)).run(owner, profile.pharmacyId)
                }
                Result.success()
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}

internal object RecoveryScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun resume(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (PharmacyLedgerDatabase.get(app).dao().recoveryControls().any {
                    it.id.startsWith(RecoveryKeys.ITEM) && RecoveryRecord.parse(it.value).state == "PENDING" }) enqueue(app)
        }
    }
    fun enqueue(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork("raad-restore21-verify",
            ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<RecoverySyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}

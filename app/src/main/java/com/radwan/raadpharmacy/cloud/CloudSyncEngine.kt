package com.radwan.raadpharmacy.cloud

import android.content.Context
import android.os.Build
import com.radwan.raadpharmacy.data.CustomerEntity
import com.radwan.raadpharmacy.data.LedgerEntryEntity
import com.radwan.raadpharmacy.data.PharmacyLedgerDatabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PrimaryKey
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresListDataFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.OffsetDateTime

class CloudSyncEngine(context: Context) {
    private val appContext = context.applicationContext
    private val client = SupabaseProvider.client
    private val dao = PharmacyLedgerDatabase.get(appContext).dao()
    private val journal = CloudSyncJournal(appContext)
    private val deviceStore = CloudDeviceStore(appContext)

    suspend fun unregisterPushToken() = globalSyncMutex.withLock {
        client.auth.awaitInitialization()
        val session = client.auth.currentSessionOrNull() ?: return@withLock
        val userId = session.user?.id ?: return@withLock
        val profile = client.from("profiles")
            .select { filter { eq("id", userId) } }
            .decodeSingle<CloudProfileRow>()

        client.from("push_tokens").update(DeletedAtPatch(nowIso())) {
            filter {
                eq("id", deviceStore.pushTokenRowId())
                eq("pharmacy_id", profile.pharmacyId)
                eq("user_id", userId)
            }
        }
    }

    suspend fun syncOnce() = globalSyncMutex.withLock {
        client.auth.awaitInitialization()
        val session = client.auth.currentSessionOrNull() ?: return@withLock
        val userId = session.user?.id ?: return@withLock
        val profile = client.from("profiles")
            .select { filter { eq("id", userId) } }
            .decodeSingle<CloudProfileRow>()

        registerDevice(profile, userId)
        registerPushToken(profile, userId)

        if (!deviceStore.isBootstrapped(profile.pharmacyId)) {
            bootstrap(profile)
            deviceStore.markBootstrapped(profile.pharmacyId)
        }

        pushPending(profile)
        val remoteCustomers = fetchCustomers()
        val remoteTransactions = fetchTransactions()
        applyRemoteSnapshot(remoteCustomers, remoteTransactions)
    }

    fun startRealtime(scope: CoroutineScope): Boolean {
        val session = client.auth.currentSessionOrNull() ?: return false
        val userId = session.user?.id ?: return false
        val channel = client.channel("raad-ledger-$userId")

        val customers = channel.postgresListDataFlow(
            table = "customers",
            primaryKey = PrimaryKey<CloudCustomerRow>("id") { it.id }
        )
        val transactions = channel.postgresListDataFlow(
            table = "transactions",
            primaryKey = PrimaryKey<CloudTransactionRow>("id") { it.id }
        )

        scope.launch {
            channel.subscribe()
        }
        scope.launch {
            client.auth.sessionStatus.collect { status ->
                if (status is SessionStatus.Authenticated) {
                    runCatching { channel.updateAuth(status.session.accessToken) }
                }
            }
        }
        scope.launch {
            combine(customers, transactions) { customerRows, transactionRows ->
                customerRows to transactionRows
            }.collect { (customerRows, transactionRows) ->
                globalSyncMutex.withLock {
                    notifyRemoteTransactions(customerRows, transactionRows)
                    applyRemoteSnapshot(customerRows, transactionRows)
                }
            }
        }
        return true
    }

    private suspend fun bootstrap(profile: CloudProfileRow) {
        val remoteCustomers = fetchCustomers()
        val remoteTransactions = fetchTransactions()
        val localCustomers = dao.getCustomers()
        val localEntries = dao.getEntries()

        val knownRemoteCustomerIds = remoteCustomers.mapTo(hashSetOf()) { it.id }
        val missingCustomers = localCustomers.filter { it.id !in knownRemoteCustomerIds }
        if (missingCustomers.isNotEmpty()) {
            client.from("customers").upsert(
                missingCustomers.map { it.toCloud(profile.pharmacyId) }
            ) { onConflict = "id" }
        }

        val knownRemoteTransactionIds = remoteTransactions.mapTo(hashSetOf()) { it.id }
        val missingTransactions = localEntries.filter { it.id !in knownRemoteTransactionIds }
        if (missingTransactions.isNotEmpty()) {
            client.from("transactions").upsert(
                missingTransactions.map { it.toCloud(profile.pharmacyId) }
            ) { onConflict = "id" }
        }

        applyRemoteSnapshot(fetchCustomers(), fetchTransactions())
    }

    private suspend fun pushPending(profile: CloudProfileRow) {
        val pending = journal.snapshot()

        pending.customerUpserts.forEach { id ->
            val local = dao.getCustomerById(id)
            if (local == null) {
                journal.markCustomerDelete(id)
                journal.clearCustomerUpsert(id)
            } else {
                client.from("customers").upsert(local.toCloud(profile.pharmacyId)) {
                    onConflict = "id"
                }
                journal.clearCustomerUpsert(id)
            }
        }

        pending.transactionUpserts.forEach { id ->
            val local = dao.getEntryById(id)
            if (local == null) {
                journal.markTransactionDelete(id)
                journal.clearTransactionUpsert(id)
            } else {
                client.from("transactions").upsert(local.toCloud(profile.pharmacyId)) {
                    onConflict = "id"
                }
                journal.clearTransactionUpsert(id)
            }
        }

        journal.snapshot().transactionDeletes.forEach { id ->
            client.from("transactions").update(DeletedAtPatch(nowIso())) {
                filter { eq("id", id) }
            }
            journal.clearTransactionDelete(id)
        }

        journal.snapshot().customerDeletes.forEach { id ->
            val deletedAt = nowIso()
            client.from("transactions").update(DeletedAtPatch(deletedAt)) {
                filter { eq("customer_id", id) }
            }
            client.from("customers").update(DeletedAtPatch(deletedAt)) {
                filter { eq("id", id) }
            }
            journal.clearCustomerDelete(id)
        }
    }

    private suspend fun registerDevice(profile: CloudProfileRow, userId: String) {
        val row = CloudDeviceWrite(
            id = deviceStore.deviceId(),
            pharmacyId = profile.pharmacyId,
            userId = userId,
            name = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "Android" },
            lastSeenAt = nowIso()
        )
        client.from("devices").upsert(row) { onConflict = "id" }
    }

    private suspend fun registerPushToken(profile: CloudProfileRow, userId: String) {
        val token = deviceStore.fcmToken()?.takeIf { it.isNotBlank() } ?: return
        client.from("push_tokens").upsert(
            CloudPushTokenWrite(
                id = deviceStore.pushTokenRowId(),
                pharmacyId = profile.pharmacyId,
                userId = userId,
                deviceId = deviceStore.deviceId(),
                token = token
            )
        ) { onConflict = "id" }
    }

    private suspend fun fetchCustomers(): List<CloudCustomerRow> =
        client.from("customers").select().decodeList()

    private suspend fun fetchTransactions(): List<CloudTransactionRow> =
        client.from("transactions").select().decodeList()

    private suspend fun notifyRemoteTransactions(
        remoteCustomers: List<CloudCustomerRow>,
        remoteTransactions: List<CloudTransactionRow>
    ) {
        val localIds = dao.getEntries().mapTo(hashSetOf()) { it.id }
        val cutoff = System.currentTimeMillis() - 30_000L
        val customerNames = remoteCustomers.associate { it.id to it.name }

        remoteTransactions
            .asSequence()
            .filter { it.deletedAt == null }
            .filter { it.id !in localIds }
            .filter { parseIso(it.createdAt) >= cutoff }
            .sortedBy { parseIso(it.createdAt) }
            .takeLast(5)
            .forEach { row ->
                val customerName = customerNames[row.customerId] ?: "الزبون"
                val amount = row.amount.toLong()
                val title = if (row.type == "PAYMENT") "تحصيل جديد من جهاز آخر" else "دين جديد من جهاز آخر"
                val body = if (row.type == "PAYMENT") {
                    "تم تسجيل تحصيل " + amount + " د.ع لحساب " + customerName
                } else {
                    "تم تسجيل دين " + amount + " د.ع على حساب " + customerName
                }
                CloudNotificationCenter.post(
                    context = appContext,
                    title = title,
                    body = body,
                    customerId = row.customerId
                )
            }
    }
    private suspend fun applyRemoteSnapshot(
        remoteCustomers: List<CloudCustomerRow>,
        remoteTransactions: List<CloudTransactionRow>
    ) {
        val pending = journal.snapshot()

        val customerMap = remoteCustomers
            .asSequence()
            .filter { it.deletedAt == null }
            .associate { it.id to it.toLocal() }
            .toMutableMap()

        pending.customerDeletes.forEach(customerMap::remove)
        pending.customerUpserts.forEach { id ->
            dao.getCustomerById(id)?.let { customerMap[id] = it }
        }

        val entryMap = remoteTransactions
            .asSequence()
            .filter { it.deletedAt == null }
            .associate { it.id to it.toLocal() }
            .toMutableMap()

        pending.transactionDeletes.forEach(entryMap::remove)
        pending.transactionUpserts.forEach { id ->
            dao.getEntryById(id)?.let { entryMap[id] = it }
        }

        val validCustomerIds = customerMap.keys
        val validEntries = entryMap.values.filter { it.customerId in validCustomerIds }

        dao.replaceAll(
            customers = customerMap.values.sortedByDescending { it.createdAt },
            entries = validEntries.sortedByDescending { it.createdAt }
        )
    }

    private fun CustomerEntity.toCloud(pharmacyId: String) = CloudCustomerWrite(
        id = id,
        pharmacyId = pharmacyId,
        name = name,
        phone = phone,
        area = area,
        address = address.takeIf { it.isNotBlank() },
        openingDebt = openingDebt,
        notes = notes.takeIf { it.isNotBlank() },
        createdAt = toIso(createdAt)
    )

    private fun LedgerEntryEntity.toCloud(pharmacyId: String) = CloudTransactionWrite(
        id = id,
        pharmacyId = pharmacyId,
        customerId = customerId,
        operationId = id,
        type = type,
        amount = amount,
        notes = details.takeIf { it.isNotBlank() },
        occurredAt = toIso(createdAt),
        createdAt = toIso(createdAt)
    )

    private fun CloudCustomerRow.toLocal() = CustomerEntity(
        id = id,
        name = name,
        phone = phone,
        area = area,
        address = address.orEmpty(),
        openingDebt = openingDebt,
        notes = notes.orEmpty(),
        createdAt = parseIso(createdAt)
    )

    private fun CloudTransactionRow.toLocal() = LedgerEntryEntity(
        id = id,
        customerId = customerId,
        type = type,
        amount = amount.toLong(),
        bottles = null,
        bottlePrice = null,
        details = notes.orEmpty(),
        createdAt = parseIso(occurredAt)
    )

    private fun toIso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun nowIso(): String = Instant.now().toString()

    private fun parseIso(value: String): Long =
        runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrDefault(System.currentTimeMillis())

    companion object {
        private val globalSyncMutex = Mutex()
    }
}

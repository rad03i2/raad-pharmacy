package com.radwan.raadpharmacy.cloud

import android.content.Context
import android.os.Build
import android.util.Log
import com.radwan.raadpharmacy.data.CustomerEntity
import com.radwan.raadpharmacy.data.LedgerEntryEntity
import com.radwan.raadpharmacy.data.PharmacyLedgerDatabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeOldRecordOrNull
import io.github.jan.supabase.realtime.decodeRecordOrNull
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
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
    private val actorNames = mutableMapOf<String, String?>()

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
        pullRemoteSnapshot()
    }

    suspend fun pullRemoteNow() = globalSyncMutex.withLock {
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull() == null) return@withLock
        pullRemoteSnapshot()
    }

    fun startRealtime(scope: CoroutineScope): Boolean {
        val session = client.auth.currentSessionOrNull() ?: return false
        val userId = session.user?.id ?: return false
        val channel = client.channel("raad-ledger-" + userId)

        val transactionChanges = channel.postgresChangeFlow<PostgresAction>("public") {
            table = "transactions"
        }
        val customerChanges = channel.postgresChangeFlow<PostgresAction>("public") {
            table = "customers"
        }

        scope.launch {
            launch {
                transactionChanges.collect { action ->
                    runCatching {
                        transactionRow(action)?.let { row ->
                            handleTransactionRealtime(row)
                            applyTransactionRealtime(row)
                        }
                    }.onFailure {
                        Log.w(TAG, "Realtime transaction sync failed", it)
                    }
                }
            }
            launch {
                customerChanges.collect { action ->
                    runCatching {
                        customerRow(action)?.let { applyCustomerRealtime(it) }
                    }.onFailure {
                        Log.w(TAG, "Realtime customer sync failed", it)
                    }
                }
            }
            runCatching { channel.subscribe() }
                .onFailure { Log.w(TAG, "Realtime channel subscription failed", it) }
        }

        scope.launch {
            client.auth.sessionStatus.collect { status ->
                if (status is SessionStatus.Authenticated) {
                    runCatching { channel.updateAuth(status.session.accessToken) }
                        .onFailure { Log.w(TAG, "Realtime auth refresh failed", it) }
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

        pullRemoteSnapshot()
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

    private suspend fun pullRemoteSnapshot() {
        val remoteCustomers = fetchCustomers()
        val remoteTransactions = fetchTransactions()
        applyRemoteSnapshot(remoteCustomers, remoteTransactions)
    }

    private fun transactionRow(action: PostgresAction): CloudTransactionRow? =
        when (action) {
            is PostgresAction.Insert -> action.decodeRecordOrNull<CloudTransactionRow>()
            is PostgresAction.Update -> action.decodeRecordOrNull<CloudTransactionRow>()
            is PostgresAction.Delete -> action.decodeOldRecordOrNull<CloudTransactionRow>()
            is PostgresAction.Select -> action.decodeRecordOrNull<CloudTransactionRow>()
        }

    private fun customerRow(action: PostgresAction): CloudCustomerRow? =
        when (action) {
            is PostgresAction.Insert -> action.decodeRecordOrNull<CloudCustomerRow>()
            is PostgresAction.Update -> action.decodeRecordOrNull<CloudCustomerRow>()
            is PostgresAction.Delete -> action.decodeOldRecordOrNull<CloudCustomerRow>()
            is PostgresAction.Select -> action.decodeRecordOrNull<CloudCustomerRow>()
        }

    private suspend fun handleTransactionRealtime(row: CloudTransactionRow) {
        if (row.deviceId == deviceStore.deviceId()) return

        val local = dao.getEntryById(row.id)
        val amount = row.amount.toLong()
        val customerName = dao.getCustomerById(row.customerId)?.name
            ?: runCatching {
                client.from("customers")
                    .select { filter { eq("id", row.customerId) } }
                    .decodeSingle<CloudCustomerRow>()
                    .name
            }.getOrNull()
            ?: "الزبون"

        val actorUserId = when {
            row.deletedAt != null -> row.deletedBy ?: row.updatedBy ?: row.createdBy
            local == null -> row.createdBy ?: row.updatedBy
            else -> row.updatedBy ?: row.createdBy
        }
        val actorName = actorNameFor(actorUserId)

        val event = when {
            row.deletedAt != null && local != null -> CloudUiEvent(
                title = "تم حذف حركة من جهاز آخر",
                message = "تم حذف حركة بقيمة " + amount + " د.ع من حساب " + customerName,
                customerId = row.customerId,
                actorName = actorName,
                kind = CloudUiEvent.Kind.DELETE
            )
            row.deletedAt == null && local == null && row.type == "PAYMENT" -> CloudUiEvent(
                title = "تحصيل جديد",
                message = "تم تسجيل تحصيل " + amount + " د.ع لحساب " + customerName,
                customerId = row.customerId,
                actorName = actorName,
                kind = CloudUiEvent.Kind.PAYMENT
            )
            row.deletedAt == null && local == null -> CloudUiEvent(
                title = "دين جديد",
                message = "تم تسجيل دين " + amount + " د.ع على حساب " + customerName,
                customerId = row.customerId,
                actorName = actorName,
                kind = CloudUiEvent.Kind.DEBT
            )
            row.deletedAt == null && local != null && (
                local.customerId != row.customerId ||
                    local.type != row.type ||
                    local.amount != amount ||
                    local.details != row.notes.orEmpty()
            ) -> CloudUiEvent(
                title = "تم تعديل حركة",
                message = "تم تحديث حركة بقيمة " + amount + " د.ع في حساب " + customerName,
                customerId = row.customerId,
                actorName = actorName,
                kind = CloudUiEvent.Kind.EDIT
            )
            else -> null
        } ?: return

        CloudUiEvents.emit(event)

        if (!CloudUiEvents.isAppForeground()) {
            val notificationTitle = event.actorName?.let { event.title + " • " + it } ?: event.title
            CloudNotificationCenter.post(
                context = appContext,
                title = notificationTitle,
                body = event.message,
                customerId = event.customerId
            )
        }
    }

    private suspend fun applyTransactionRealtime(row: CloudTransactionRow) {
        val pending = journal.snapshot()
        if (
            row.id in pending.transactionUpserts ||
            row.id in pending.transactionDeletes
        ) {
            return
        }

        if (row.deletedAt != null) {
            dao.deleteEntryById(row.id)
            return
        }

        if (dao.getCustomerById(row.customerId) == null) {
            val customer = runCatching {
                client.from("customers")
                    .select { filter { eq("id", row.customerId) } }
                    .decodeSingle<CloudCustomerRow>()
            }.getOrNull()

            if (customer != null && customer.deletedAt == null) {
                dao.insertCustomer(customer.toLocal())
            }
        }

        if (dao.getCustomerById(row.customerId) != null) {
            dao.insertEntry(row.toLocal())
        }
    }

    private suspend fun applyCustomerRealtime(row: CloudCustomerRow) {
        val pending = journal.snapshot()
        if (
            row.id in pending.customerUpserts ||
            row.id in pending.customerDeletes
        ) {
            return
        }

        if (row.deletedAt != null) {
            dao.getEntriesForCustomer(row.id)
                .forEach { dao.deleteEntryById(it.id) }
            dao.deleteCustomerById(row.id)
        } else {
            dao.insertCustomer(row.toLocal())
        }
    }

    private suspend fun actorNameFor(userId: String?): String? {
        if (userId.isNullOrBlank()) return null
        if (actorNames.containsKey(userId)) return actorNames[userId]

        val name = runCatching {
            client.from("profiles")
                .select { filter { eq("id", userId) } }
                .decodeSingle<CloudProfileRow>()
                .takeUnless { it.isHidden }
                ?.displayName
        }.getOrNull()

        actorNames[userId] = name
        return name
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
        deviceId = deviceStore.deviceId(),
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
        private const val TAG = "CloudSyncEngine"
        private val globalSyncMutex = Mutex()
    }
}

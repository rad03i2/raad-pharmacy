package com.radwan.raadpharmacy.cloud

import android.content.Context
import com.radwan.raadpharmacy.BuildConfig
import com.radwan.raadpharmacy.security.AppSecurityStore
import android.os.Build
import android.util.Log
import com.radwan.raadpharmacy.data.CustomerEntity
import com.radwan.raadpharmacy.data.LedgerEntryEntity
import com.radwan.raadpharmacy.data.PharmacyLedgerDatabase
import com.radwan.raadpharmacy.data.LedgerReleaseCleanup
import com.radwan.raadpharmacy.data.BackupJson
import com.radwan.raadpharmacy.data.toEntity
import kotlinx.coroutines.CancellationException
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeOldRecordOrNull
import io.github.jan.supabase.realtime.decodeRecordOrNull
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
    private val actors = EntryActorStore.get(appContext)
    private val syncPrefs = appContext.getSharedPreferences("raad_cloud_sync_state_v2", Context.MODE_PRIVATE)
    private val ledgerPuller = CloudLedgerPuller(syncPrefs, ::fetchCustomers, ::fetchTransactions) { customers, transactions, full ->
        if (full) applyRemoteSnapshot(customers, transactions)
        else {
            customers.forEach { applyCustomerRealtime(it) }
            transactions.forEach { applyTransactionRealtime(it) }
        }
        actors.rememberActors(transactions)
        CloudLedgerMediaRuntime.enqueue(appContext, customers)
    }

    suspend fun unregisterPushToken() = globalSyncMutex.withLock {
        LedgerReleaseCleanup.clearOnce(appContext)
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

    suspend fun refreshPushRegistration() = pushRegistrationMutex.withLock {
        client.auth.awaitInitialization()
        val userId = checkNotNull(client.auth.currentSessionOrNull()?.user?.id) { "Push registration session is not ready" }
        val profile = client.from("profiles").select { filter { eq("id", userId) } }.decodeSingle<CloudProfileRow>()
        registerDevice(profile, userId)
        registerPushToken(profile, userId)
    }

    suspend fun syncOnce() = ledgerOperation {
        if (dao.restoreHold() == "1") return@ledgerOperation
        journal.importLegacy()
        LedgerReleaseCleanup.clearOnce(appContext)
        client.auth.awaitInitialization()
        val userId = requireCloudSyncOwner(client.auth.currentSessionOrNull()?.user?.id)
        val profile = client.from("profiles")
            .select { filter { eq("id", userId) } }
            .decodeSingle<CloudProfileRow>()

        syncBothDirections(upload = {
            registerDevice(profile, userId)
            if (!deviceStore.isBootstrapped(profile.pharmacyId)) {
                bootstrap(profile)
                deviceStore.markBootstrapped(profile.pharmacyId)
            }
            pushPending(profile)
        }, receive = { pullRemoteDelta() })
    }

    suspend fun flushPendingOnly() = ledgerOperation {
        if (dao.restoreHold() == "1") return@ledgerOperation
        journal.importLegacy()
        LedgerReleaseCleanup.clearOnce(appContext)
        client.auth.awaitInitialization()
        val userId = requireCloudSyncOwner(client.auth.currentSessionOrNull()?.user?.id)
        val profile = client.from("profiles")
            .select { filter { eq("id", userId) } }
            .decodeSingle<CloudProfileRow>()

        registerDevice(profile, userId)
        pushPending(profile)
    }

    suspend fun pullRemoteNow(forceSnapshot: Boolean = false) = ledgerOperation {
        if (dao.restoreHold() == "1") return@ledgerOperation
        journal.importLegacy()
        LedgerReleaseCleanup.clearOnce(appContext)
        client.auth.awaitInitialization()
        requireCloudSyncOwner(client.auth.currentSessionOrNull()?.user?.id)
        pullRemoteDelta(forceSnapshot)
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
                            globalSyncMutex.withLock {
                                if (dao.restoreHold() != "1") applyTransactionRealtime(row)
                            }
                        }
                    }.onFailure {
                        Log.w(TAG, "Realtime transaction sync failed", it)
                    }
                }
            }
            launch {
                customerChanges.collect { action ->
                    runCatching {
                        customerRow(action)?.let { row -> globalSyncMutex.withLock {
                            if (dao.restoreHold() != "1") applyCustomerRealtime(row)
                        } }
                    }.onFailure {
                        Log.w(TAG, "Realtime customer sync failed", it)
                    }
                }
            }
            try { withTimeout(15_000L) { channel.subscribe(blockUntilSubscribed = true) } }
            catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                Log.w(TAG, "Ledger subscription failed; scheduling recovery")
                CloudSyncRuntime.recoverLedgerRealtime(appContext)
            }
        }
        scope.launch {
            channel.status.watchNotificationConnection(onConnected = {
                CloudSyncRuntime.ledgerRealtimeConnected()
                // Catch anything committed before a successful join or during a reconnect.
                scope.launch {
                    try { pullRemoteNow() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Log.w(TAG, "Ledger catch-up deferred after join") }
                }
            }, onLost = { CloudSyncRuntime.recoverLedgerRealtime(appContext) })
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

    }

    private suspend fun pushPending(profile: CloudProfileRow) {
        DurableCloudDrain(dao) { row ->
            val id = row.entityId
            when {
                row.kind == "CUSTOMER" && row.action == "UPSERT" -> {
                    val local = BackupJson.parse(row.payload).customers.single().toEntity()
                    client.from("customers").upsert(local.toCloud(profile.pharmacyId)) { onConflict = "id" }
                    // The server trigger uses the new customer's UUID as its idempotent
                    // notification event ID. Fast FCM dispatch is a best effort; the
                    // server's durable due-event worker remains the offline fallback.
                    if (local.createdAt >= System.currentTimeMillis() - 86_400_000L) {
                        CloudPushDispatcher.requestEvent(appContext, local.id)
                    }
                }
                row.kind == "ENTRY" && row.action == "UPSERT" -> {
                    val local = BackupJson.parse(row.payload).entries.single().toEntity()
                    client.from("transactions").upsert(local.toCloud(profile.pharmacyId)) { onConflict = "id" }
                    // Schedule durable delivery before acknowledging this upload. A retry
                    // reuses the transaction id; the server ignores identical financial updates.
                    CloudPushDispatcher.request(appContext, id)
                    CloudUploadFeedback.post(appContext, row)
                }
                row.kind == "ENTRY" -> {
                    client.from("transactions").update(DeletedAtDevicePatch(toIso(row.occurredAt), deviceStore.deviceId())) {
                        filter { eq("id", id) }
                    }
                    CloudPushDispatcher.request(appContext, id)
                    CloudUploadFeedback.post(appContext, row)
                }
                else -> {
                    val deletedAt = toIso(row.occurredAt)
                    client.from("transactions").update(DeletedAtDevicePatch(deletedAt, deviceStore.deviceId())) {
                        filter { eq("customer_id", id) }
                    }
                    client.from("customers").update(DeletedAtPatch(deletedAt)) { filter { eq("id", id) } }
                }
            }
        }.flush()
    }

    private suspend fun registerDevice(profile: CloudProfileRow, userId: String) {
        CloudDeviceRegistration.register(appContext, profile, userId)
    }

    private suspend fun registerPushToken(profile: CloudProfileRow, userId: String) = tokenRegistrationMutex.withLock {
        val token = deviceStore.fcmToken()?.takeIf { it.isNotBlank() } ?: return@withLock
        client.from("push_tokens").upsert(
            CloudPushTokenWrite(
                id = deviceStore.pushTokenRowId(),
                pharmacyId = profile.pharmacyId,
                userId = userId,
                deviceId = deviceStore.deviceId(),
                token = token,
                appVersionCode = BuildConfig.VERSION_CODE,
                hideNotificationDetails = AppSecurityStore(appContext).state().hideAmounts
            )
        ) { onConflict = "id" }
        deviceStore.markPushRegistered(token, BuildConfig.VERSION_CODE)
    }

    private suspend fun fetchCustomers(since: String? = null): List<CloudCustomerRow> {
        val rows = mutableListOf<CloudCustomerRow>()
        do {
            val afterId = rows.lastOrNull()?.id
            val page = client.from("customers").select {
                filter {
                    if (since != null) gte("updated_at", since)
                    if (afterId != null) gt("id", afterId)
                }
                order("id", Order.ASCENDING)
                range(0L..99L)
            }.decodeList<CloudCustomerRow>()
            rows.addAll(page)
        } while (page.size == 100)
        return rows
    }

    private suspend fun fetchTransactions(since: String? = null): List<CloudTransactionRow> {
        val rows = mutableListOf<CloudTransactionRow>()
        do {
            val afterId = rows.lastOrNull()?.id
            val page = client.from("transactions").select {
                filter {
                    if (since != null) gte("updated_at", since)
                    if (afterId != null) gt("id", afterId)
                }
                order("id", Order.ASCENDING)
                range(0L..99L)
            }.decodeList<CloudTransactionRow>()
            rows.addAll(page)
        } while (page.size == 100)
        return rows
    }

    private suspend fun pullRemoteDelta(forceSnapshot: Boolean = false) = ledgerPuller.pull(forceSnapshot)

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

    private suspend fun applyTransactionRealtime(row: CloudTransactionRow) {
        actors.rememberActors(listOf(row))
        val pending = journal.snapshot()
        if (
            row.id in pending.transactionUpserts ||
            row.id in pending.transactionDeletes
        ) {
            return
        }

        if (row.deletedAt != null) {
            dao.applyCloudEntry(row.id, null)
            return
        }

        if (dao.getCustomerById(row.customerId) == null) {
            val customer = runCatching {
                client.from("customers")
                    .select { filter { eq("id", row.customerId) } }
                    .decodeSingle<CloudCustomerRow>()
            }.getOrNull()

            if (customer != null && customer.deletedAt == null) {
                dao.applyCloudCustomer(customer.id, customer.toLocal())
            }
        }

        if (dao.getCustomerById(row.customerId) != null) {
            val local = row.toLocal()
            dao.applyCloudEntry(row.id, local)
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
            dao.applyCloudCustomer(row.id, null)
            if (dao.getCustomerById(row.id) == null)
                com.radwan.raadpharmacy.customer.CustomerPhotoStore(appContext).remove(row.id)
        } else {
            dao.applyCloudCustomer(row.id, row.toLocal())
            CloudLedgerMediaRuntime.enqueue(appContext, listOf(row))
        }
    }

    private suspend fun applyRemoteSnapshot(
        remoteCustomers: List<CloudCustomerRow>, remoteTransactions: List<CloudTransactionRow>
    ) {
        dao.applyCloudSnapshot(
            remoteCustomers.filter { it.deletedAt == null }.map { it.toLocal() },
            remoteTransactions.filter { it.deletedAt == null }.map { it.toLocal() }
        )
    }

    /** Explicit reconciliation chooses current server state; restored offline operations stay quarantined. */
    suspend fun reconcileRestoredFromServer() = globalSyncMutex.withLock {
        check(dao.restoreHold() == "1") { "لا توجد استعادة محلية معلقة." }
        client.auth.awaitInitialization()
        val userId = checkNotNull(client.auth.currentSessionOrNull()?.user?.id) { "سجل الدخول أولًا." }
        val profile = client.from("profiles").select { filter { eq("id", userId) } }.decodeSingle<CloudProfileRow>()
        val customers = fetchCustomers()
        val transactions = fetchTransactions()
        val validCustomers = customers.filter { it.deletedAt == null }.map { it.toLocal() }
        val ids = validCustomers.mapTo(hashSetOf()) { it.id }
        val validEntries = transactions.filter { it.deletedAt == null && it.customerId in ids }.map { it.toLocal() }
        // Keep the hold on any network/storage error. Clearing it is the final step.
        // Snapshot and journal were encrypted in Recovery at restore; preserve any post-restore writes as well.
        com.radwan.raadpharmacy.backup.LocalBackupEngine.get(appContext).protectCurrentBeforeReconciliation()
        check(appContext.getSharedPreferences("raad_cloud_sync_journal", Context.MODE_PRIVATE).edit().clear().commit())
        deviceStore.markBootstrapped(profile.pharmacyId)
        CloudLedgerPuller.invalidate(syncPrefs)
        // Do not let the normal photo fallback re-upload pictures from the restored archive.
        com.radwan.raadpharmacy.customer.CustomerPhotoStore(appContext).rebuildFromBackup(emptyList())
        dao.completeReconciliation(validCustomers, validEntries)
        com.radwan.raadpharmacy.backup.LocalBackupEngine.get(appContext).wake()
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
        createdByName = createdByName,
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
        createdAt = parseIso(createdAt),
        createdByName = createdByName.orEmpty()
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
        private suspend fun <T> ledgerOperation(block: suspend () -> T): T = globalSyncMutex.withLock {
            withTimeout(30_000L) { block() }
        }
        suspend fun <T> withLedgerSyncLock(block: suspend () -> T): T = globalSyncMutex.withLock { block() }
        private val pushRegistrationMutex = Mutex()
        private val tokenRegistrationMutex = Mutex()
    }
}

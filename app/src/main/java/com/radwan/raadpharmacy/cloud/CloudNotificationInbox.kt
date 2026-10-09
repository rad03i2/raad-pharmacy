package com.radwan.raadpharmacy.cloud

import android.content.Context
import com.radwan.raadpharmacy.data.PharmacyLedgerDatabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecordOrNull
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CloudNotificationInbox(context: Context) {
    private val appContext = context.applicationContext
    private val client = SupabaseProvider.client
    private val dao = PharmacyLedgerDatabase.get(appContext).dao()
    private val deviceStore = CloudDeviceStore(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { store ->
        if (!store.contains(KEY_FEATURE_START_AT)) {
            store.edit().putLong(KEY_FEATURE_START_AT, System.currentTimeMillis()).apply()
        }
    }

    suspend fun catchUp() = catchUpMutex.withLock {
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull() == null) return@withLock
        val startAt = prefs.getLong(KEY_CURSOR_AT,
            prefs.getLong(KEY_FEATURE_START_AT, System.currentTimeMillis()))
        val snapshotEnd = java.time.Instant.now().toString()
        var offset = 0L
        var allDelivered = true
        var latestAt = startAt
        do {
            val rows = client.from("notification_events").select {
                filter {
                    gte("created_at", java.time.Instant.ofEpochMilli(startAt).toString())
                    lte("created_at", snapshotEnd)
                }
                order("created_at", Order.ASCENDING)
                order("id", Order.ASCENDING)
                range(offset..(offset + 99L))
            }.decodeList<CloudNotificationEventRow>()
            if (rows.isEmpty()) break
            deliver(rows)
            allDelivered = allDelivered && rows.all { isSeen(it.id) }
            latestAt = maxOf(latestAt, rows.maxOf { parseIso(it.createdAt) })
            offset += rows.size
        } while (rows.size == 100)
        if (allDelivered) prefs.edit().putLong(KEY_CURSOR_AT, latestAt).apply()
    }

    suspend fun deliverPush(row: CloudNotificationEventRow) {
        deliver(listOf(row))
    }

    fun startRealtime(scope: CoroutineScope): Boolean {
        val session = client.auth.currentSessionOrNull() ?: return false
        val userId = session.user?.id ?: return false
        val channel = client.channel("raad-notifications-" + userId)
        val inserts = channel.postgresChangeFlow<PostgresAction.Insert>("public") {
            table = "notification_events"
        }

        scope.launch {
            launch {
                inserts.collect { action ->
                    val row = action.decodeRecordOrNull<CloudNotificationEventRow>() ?: return@collect
                    runCatching { deliverPush(row) }
                }
            }
            runCatching { channel.subscribe() }
        }

        scope.launch {
            client.auth.sessionStatus.collect { status ->
                if (status is SessionStatus.Authenticated) {
                    runCatching { channel.updateAuth(status.session.accessToken) }
                }
            }
        }
        return true
    }

    private suspend fun deliver(rows: List<CloudNotificationEventRow>) {
        for ((index, row) in rows.withIndex()) {
            deliveryMutex.withLock {
                if (!CloudSyncScheduler.isEnabled(appContext)) return@withLock
                client.auth.awaitInitialization()
                val userId = client.auth.currentSessionOrNull()?.user?.id ?: return@withLock
                if (!row.isAddressedTo(userId)) {
                    rememberSeen(listOf(row.id))
                    return@withLock
                }
                if (row.eventType == "TEAM_MESSAGE" && !CloudTeamMessageStore(appContext).receive(row)) {
                    rememberSeen(listOf(row.id))
                    return@withLock
                }
                if (isSeen(row.id)) return@withLock
                if (row.actorDeviceId == deviceStore.deviceId()) {
                    rememberSeen(listOf(row.id))
                    return@withLock
                }
                val name = row.customerId?.let { dao.getCustomerById(it)?.name } ?: "الزبون"
                val item = row.toExternal(name)
                val wait = 3_000L - (android.os.SystemClock.elapsedRealtime() - lastAlertAt)
                if (wait > 0L) delay(wait)
                if (!CloudSyncScheduler.isEnabled(appContext)) return@withLock
                val posted = CloudNotificationCenter.post(
                    appContext, item.title, item.body, item.customerId, true, item.id,
                    openSettings = row.eventType in setOf("TEAM_ALERT", "TEAM_MESSAGE"), isMessage = row.eventType == "TEAM_MESSAGE"
                )
                if (posted) {
                    lastAlertAt = android.os.SystemClock.elapsedRealtime()
                    rememberSeen(listOf(row.id))
                }
                if (CloudUiEvents.isAppForeground()) {
                    CloudUiEvents.emit(CloudUiEvent(
                        title = row.uiTitle(), message = item.body,
                        customerId = item.customerId, actorName = row.actorDisplayName,
                        kind = row.uiKind()
                    ))
                }
            }
            if (index < rows.lastIndex) delay(3_000L)
        }
    }

    private fun CloudNotificationEventRow.toExternal(customerName: String): CloudExternalNotification {
        val amountText = amount.toLong().toString() + " د.ع"
        val actor = actorDisplayName?.takeIf { it.isNotBlank() } ?: "مستخدم آخر"
        val title: String
        val body: String

        when (eventType) {
            "TEAM_MESSAGE" -> {
                title = "رسالة من " + actor
                body = messageBody.orEmpty()
            }
            "TEAM_ALERT" -> {
                title = "تنبيه من " + actor
                body = actor + " يطلب انتباهك. افتح التطبيق للتواصل."
            }
            "PAYMENT_CREATED" -> {
                title = "تحصيل جديد • " + actor
                body = "سجّل " + actor + " تحصيل " + amountText + " لحساب " + customerName
            }
            "DEBT_CREATED" -> {
                title = "دين جديد • " + actor
                body = "سجّل " + actor + " دين " + amountText + " على حساب " + customerName
            }
            "TRANSACTION_UPDATED" -> {
                title = "تعديل حركة • " + actor
                body = "عدّل " + actor + " حركة بقيمة " + amountText + " في حساب " + customerName
            }
            "TRANSACTION_DELETED" -> {
                title = "حذف حركة • " + actor
                body = "حذف " + actor + " حركة بقيمة " + amountText + " من حساب " + customerName
            }
            else -> {
                title = "تحديث سحابي • " + actor
                body = "تم تحديث حساب " + customerName
            }
        }

        return CloudExternalNotification(
            id = id,
            title = title,
            body = body,
            customerId = customerId
        )
    }

    private fun CloudNotificationEventRow.uiTitle(): String = when (eventType) {
        "TEAM_MESSAGE" -> "رسالة جديدة"
        "TEAM_ALERT" -> "تنبيه من مستخدم"
        "PAYMENT_CREATED" -> "تحصيل جديد"
        "DEBT_CREATED" -> "دين جديد"
        "TRANSACTION_UPDATED" -> "تم تعديل حركة"
        "TRANSACTION_DELETED" -> "تم حذف حركة"
        else -> "تحديث سحابي"
    }

    private fun CloudNotificationEventRow.uiKind(): CloudUiEvent.Kind = when (eventType) {
        "PAYMENT_CREATED" -> CloudUiEvent.Kind.PAYMENT
        "DEBT_CREATED" -> CloudUiEvent.Kind.DEBT
        "TRANSACTION_UPDATED" -> CloudUiEvent.Kind.EDIT
        "TRANSACTION_DELETED" -> CloudUiEvent.Kind.DELETE
        else -> CloudUiEvent.Kind.INFO
    }

    private fun parseIso(value: String): Long =
        runCatching { java.time.Instant.parse(value).toEpochMilli() }
            .recoverCatching {
                java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
            }
            .getOrDefault(0L)

    private fun isSeen(id: String): Boolean =
        prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().contains(id)

    private fun rememberSeen(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val current = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toMutableList()
        ids.forEach { id ->
            current.remove(id)
            current.add(id)
        }
        val trimmed = current.takeLast(MAX_SEEN).toSet()
        prefs.edit().putStringSet(KEY_SEEN, trimmed).apply()
    }

    companion object {
        private val deliveryMutex = Mutex()
        private val catchUpMutex = Mutex()
        private var lastAlertAt = -3_000L
        private const val PREFS = "raad_cloud_notification_inbox_v1"
        private const val KEY_CURSOR_AT = "catchup_cursor_at"
        private const val KEY_SEEN = "seen_ids"
        private const val KEY_FEATURE_START_AT = "feature_start_at"
        private const val MAX_SEEN = 5_000
    }
}

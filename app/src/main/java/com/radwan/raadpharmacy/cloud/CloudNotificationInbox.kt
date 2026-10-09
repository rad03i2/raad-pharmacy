package com.radwan.raadpharmacy.cloud

import android.content.Context
import android.content.Intent
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
    private val client by lazy { SupabaseProvider.client }
    private val dao by lazy { PharmacyLedgerDatabase.get(appContext).dao() }
    private val deviceStore = CloudDeviceStore(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { store ->
        if (!store.contains(KEY_FEATURE_START_AT)) {
            store.edit().putLong(KEY_FEATURE_START_AT, System.currentTimeMillis()).apply()
        }
    }

    suspend fun catchUp(forceRecovery: Boolean = false) = catchUpMutex.withLock {
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull() == null) return@withLock false
        val featureStart = prefs.getLong(KEY_FEATURE_START_AT, System.currentTimeMillis())
        val startAt = if (forceRecovery) featureStart else prefs.getLong(KEY_CURSOR_AT, featureStart)
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
            deliver(rows, paceBacklog = true, forceRecovery = forceRecovery)
            // Native-managed events belong to FCM. Advancing the recovery cursor is
            // routing, not evidence that Android displayed them; never mark them seen here.
            allDelivered = allDelivered && rows.all { isSeen(it.id) || (!forceRecovery && usesManagedPush(it)) }
            latestAt = maxOf(latestAt, rows.maxOf { parseIso(it.createdAt) })
            offset += rows.size
        } while (rows.size == 100)
        if (allDelivered) prefs.edit().putLong(KEY_CURSOR_AT, latestAt).apply()
        allDelivered
    }

    /** Display from the FCM callback using only persisted identity and local data. */
    suspend fun deliverPushImmediately(row: CloudNotificationEventRow): Boolean = deliveryMutex.withLock {
        if (!CloudSyncScheduler.isEnabled(appContext)) return@withLock true
        val userId = deviceStore.pushUserId() ?: return@withLock false
        val pharmacyId = deviceStore.pushPharmacyId() ?: return@withLock false
        if (row.pharmacyId != pharmacyId || !row.isAddressedTo(userId) || row.actorDeviceId == deviceStore.deviceId()) {
            rememberSeen(listOf(row.id))
            return@withLock true
        }
        if (row.eventType == "TEAM_MESSAGE") {
            val cache = CloudTeamMessageCache.get(appContext)
            cache.restore(userId)
            if (!row.isUnreadMessageFor(userId) || cache.wasRead(userId, row.id)) {
                rememberSeen(listOf(row.id))
                return@withLock true
            }
            cache.merge(userId, listOf(row))
        }
        if (isSeen(row.id)) return@withLock true
        val name = row.customerName ?: if (row.privacyRedacted) "الزبون"
            else row.customerId?.let { dao.getCustomerById(it)?.name } ?: "الزبون"
        val item = row.toExternal(name)
        val posted = CloudNotificationCenter.post(appContext, item.title, item.body, item.customerId, true, item.id,
            openSettings = row.eventType in setOf("TEAM_ALERT", "TEAM_MESSAGE"), isMessage = row.eventType == "TEAM_MESSAGE",
            forceHidden = row.privacyRedacted)
        if (posted) {
            lastAlertAt = android.os.SystemClock.elapsedRealtime()
            rememberSeen(listOf(row.id))
            acknowledgeDisplayed(row.id)
        }
        posted
    }

    internal fun wasDelivered(id: String): Boolean = isSeen(id)

    /** FCM delivers data extras on the launcher Intent when Android displayed the notification. */
    fun markSystemNotificationOpened(intent: Intent): Boolean {
        if (intent.getStringExtra("native_display") != "1" || !CloudSyncScheduler.isEnabled(appContext)) return false
        val userId = deviceStore.pushUserId() ?: return false
        val pharmacyId = deviceStore.pushPharmacyId() ?: return false
        if (intent.getStringExtra("pharmacy_id") != pharmacyId) return false
        val type = intent.getStringExtra("event_type")
        if (type in setOf("TEAM_ALERT", "TEAM_MESSAGE") && intent.getStringExtra("recipient_user_id") != userId) return false
        val eventId = intent.getStringExtra("event_id")?.takeIf(String::isNotBlank) ?: return false
        rememberSeen(listOf(eventId))
        acknowledgeDisplayed(eventId)
        return true
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

    private suspend fun deliver(rows: List<CloudNotificationEventRow>, paceBacklog: Boolean = false,
        forceRecovery: Boolean = false) {
        for ((index, row) in rows.withIndex()) {
            // Auth refresh and message reads may use the network. Keep them outside
            // the display lock so a live FCM callback cannot wait behind catch-up I/O.
            if (!CloudSyncScheduler.isEnabled(appContext)) return
            client.auth.awaitInitialization()
            val userId = client.auth.currentSessionOrNull()?.user?.id ?: return
            val addressed = row.isAddressedTo(userId)
            val unread = !addressed || row.eventType != "TEAM_MESSAGE" || CloudTeamMessageStore(appContext).receive(row)
            deliveryMutex.withLock {
                if (!CloudSyncScheduler.isEnabled(appContext)) return@withLock
                if (!addressed) {
                    rememberSeen(listOf(row.id))
                    return@withLock
                }
                if (!unread) {
                    rememberSeen(listOf(row.id))
                    return@withLock
                }
                if (isSeen(row.id)) return@withLock
                if (row.actorDeviceId == deviceStore.deviceId()) {
                    rememberSeen(listOf(row.id))
                    return@withLock
                }
                val name = row.customerName ?: row.customerId?.let { dao.getCustomerById(it)?.name } ?: "الزبون"
                val item = row.toExternal(name)
                if (!CloudSyncScheduler.isEnabled(appContext)) return@withLock
                // FCM owns direct external display for server-managed v52+ events.
                // Realtime emits the internal banner. Explicit queue-loss recovery
                // can render durable events that FCM reported discarded.
                val native = !forceRecovery && usesManagedPush(row)
                val posted = if (native) CloudNotificationCenter.hasActiveEvent(appContext, row.id)
                    else CloudNotificationCenter.post(
                    appContext, item.title, item.body, item.customerId, true, item.id,
                    openSettings = row.eventType in setOf("TEAM_ALERT", "TEAM_MESSAGE"), isMessage = row.eventType == "TEAM_MESSAGE",
                    forceHidden = row.privacyRedacted
                )
                if (posted) {
                    lastAlertAt = android.os.SystemClock.elapsedRealtime()
                    rememberSeen(listOf(row.id))
                    acknowledgeDisplayed(row.id)
                }
                if (CloudUiEvents.isAppForeground()) {
                    CloudUiEvents.emit(CloudUiEvent(
                        title = row.uiTitle(), message = item.body,
                        customerId = item.customerId, actorName = row.actorDisplayName,
                        kind = row.uiKind()
                    ))
                }
            }
            // Avoid artificial latency for single live Realtime/FCM events.
            // Only space out bulk alerts accumulated while offline.
            if (paceBacklog && (forceRecovery || !usesManagedPush(row)) && index < rows.lastIndex) delay(3_000L)
        }
    }

    internal fun usesManagedPush(row: CloudNotificationEventRow): Boolean =
        row.pushServerManaged && deviceStore.usesManagedPush() &&
            parseIso(row.createdAt) >= System.currentTimeMillis() - 7 * 86400_000L

    private fun acknowledgeDisplayed(eventId: String) {
        android.util.Log.i("RaadPush", "event_id=$eventId display_observed_at=${System.currentTimeMillis()}")
        CloudNotificationReceiptWorker.enqueue(appContext, eventId)
    }

    private fun CloudNotificationEventRow.toExternal(customerName: String): CloudExternalNotification {
        // Prefer the immutable server snapshot; the customer might not have synchronized yet.
        val name = this.customerName?.takeIf { it.isNotBlank() } ?: customerName
        val amountText = java.text.NumberFormat.getNumberInstance(java.util.Locale.US)
            .format(amount.toLong()) + " د.ع"
        val balanceText = balanceAfter?.takeIf { it.isFinite() }?.let {
            "\nالرصيد الحالي: " +
                java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(it.toLong()) + " د.ع"
        }.orEmpty()
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
                if (balanceAfter == 0.0) {
                    title = "تم تسديد الحساب بالكامل"
                    body = "سجّل " + actor + " تسديداً كاملاً لحساب " + name +
                        ".\nالمبلغ المسدد: " + amountText + balanceText
                } else {
                    title = "تحصيل جديد — صيدلية رعد"
                    body = "سجّل " + actor + " تحصيلاً بقيمة " + amountText +
                        " من حساب " + name + "." + balanceText
                }
            }
            "DEBT_CREATED" -> {
                title = "دين جديد — صيدلية رعد"
                body = "سجّل " + actor + " ديناً بقيمة " + amountText +
                    " على حساب " + name + "." + balanceText
            }
            "TRANSACTION_UPDATED" -> {
                title = "تم تعديل حركة مالية"
                body = "عدّل " + actor + " حركة " + (if (transactionType == "PAYMENT") "تحصيل" else "دين") +
                    " في حساب " + name + ".\nالمبلغ الجديد: " + amountText + balanceText
            }
            "TRANSACTION_DELETED" -> {
                title = "تم حذف حركة مالية"
                body = "حذف " + actor + " حركة " + (if (transactionType == "PAYMENT") "تحصيل" else "دين") +
                    " من حساب " + name + ".\nالقيمة: " + amountText + balanceText
            }
            else -> {
                title = "تحديث سحابي — " + actor
                body = "تم تحديث حساب " + name
            }
        }
        return CloudExternalNotification(id = id, title = title, body = body, customerId = customerId)
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
        prefs.edit().putStringSet(KEY_SEEN, trimmed).commit()
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

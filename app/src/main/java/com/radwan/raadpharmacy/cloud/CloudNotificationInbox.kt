package com.radwan.raadpharmacy.cloud

import android.content.Context
import com.radwan.raadpharmacy.data.PharmacyLedgerDatabase
import com.radwan.raadpharmacy.notifications.PixabaySoundAssets
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecordOrNull
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class CloudNotificationInbox(context: Context) {
    private val appContext = context.applicationContext
    private val client = SupabaseProvider.client
    private val dao = PharmacyLedgerDatabase.get(appContext).dao()
    private val deviceStore = CloudDeviceStore(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun catchUp() {
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull() == null) return

        val rows = client.from("notification_events")
            .select {
                order("created_at", Order.DESCENDING)
                limit(100)
            }
            .decodeList<CloudNotificationEventRow>()

        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            rememberSeen(rows.map { it.id })
            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
            return
        }

        val unseen = rows
            .asSequence()
            .filterNot { isSeen(it.id) }
            .sortedBy { it.createdAt }
            .toList()

        if (unseen.isEmpty()) return

        val mine = unseen.filter { it.actorDeviceId == deviceStore.deviceId() }
        rememberSeen(mine.map { it.id })

        val remote = unseen.filter { it.actorDeviceId != deviceStore.deviceId() }
        if (remote.isEmpty()) return

        deliver(remote, catchUp = true)
        rememberSeen(remote.map { it.id })
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
                    if (isSeen(row.id)) return@collect
                    if (row.actorDeviceId == deviceStore.deviceId()) {
                        rememberSeen(listOf(row.id))
                        return@collect
                    }
                    runCatching {
                        deliver(listOf(row), catchUp = false)
                        rememberSeen(listOf(row.id))
                    }
                }
            }
            runCatching { channel.subscribe() }
        }
        return true
    }

    private suspend fun deliver(rows: List<CloudNotificationEventRow>, catchUp: Boolean) {
        val external = rows.map { row ->
            val customerName = row.customerId
                ?.let { dao.getCustomerById(it)?.name }
                ?: "الزبون"
            row.toExternal(customerName)
        }

        if (CloudUiEvents.isAppForeground()) {
            PixabaySoundAssets.playNotification(appContext)

            if (catchUp && rows.size > 1) {
                CloudUiEvents.emit(
                    CloudUiEvent(
                        title = "وصلت ${rows.size} عمليات جديدة",
                        message = "تمت مزامنة العمليات التي حدثت أثناء عدم اتصال هذا الهاتف.",
                        kind = CloudUiEvent.Kind.REFRESH
                    )
                )
            } else {
                val row = rows.last()
                val item = external.last()
                CloudUiEvents.emit(
                    CloudUiEvent(
                        title = item.title,
                        message = item.body,
                        customerId = item.customerId,
                        actorName = row.actorDisplayName,
                        kind = row.uiKind()
                    )
                )
            }

            CloudNotificationCenter.postBatch(
                context = appContext,
                events = external,
                audible = false
            )
        } else {
            CloudNotificationCenter.postBatch(
                context = appContext,
                events = external,
                audible = true
            )
        }
    }

    private fun CloudNotificationEventRow.toExternal(customerName: String): CloudExternalNotification {
        val amountText = amount.toLong().toString() + " د.ع"
        val actor = actorDisplayName?.takeIf { it.isNotBlank() } ?: "مستخدم آخر"
        val title: String
        val body: String

        when (eventType) {
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

    private fun CloudNotificationEventRow.uiKind(): CloudUiEvent.Kind = when (eventType) {
        "PAYMENT_CREATED" -> CloudUiEvent.Kind.PAYMENT
        "DEBT_CREATED" -> CloudUiEvent.Kind.DEBT
        "TRANSACTION_UPDATED" -> CloudUiEvent.Kind.EDIT
        "TRANSACTION_DELETED" -> CloudUiEvent.Kind.DELETE
        else -> CloudUiEvent.Kind.INFO
    }

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
        private const val PREFS = "raad_cloud_notification_inbox_v1"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_SEEN = "seen_ids"
        private const val MAX_SEEN = 300
    }
}

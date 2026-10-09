package com.radwan.raadpharmacy.cloud

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun CloudNotificationEventRow.isUnreadMessageFor(owner: String): Boolean =
    eventType == "TEAM_MESSAGE" && recipientUserId == owner && actorUserId != null &&
        actorUserId != owner && messageReadAt == null && !messageBody.isNullOrBlank()

internal fun messageTimestamp(row: CloudNotificationEventRow): Long =
    runCatching { java.time.Instant.parse(row.createdAt).toEpochMilli() }
        .recoverCatching { java.time.OffsetDateTime.parse(row.createdAt).toInstant().toEpochMilli() }.getOrDefault(0L)

/** Account-scoped inbox with durable offline read acknowledgements. IO callers own disk access. */
internal class CloudTeamMessageCache(context: Context) {
    private val prefs by lazy { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val state = MutableStateFlow<Entry?>(null)
    val changes = state.asStateFlow()
    val refreshMutex = Mutex()

    fun current(owner: String?): List<CloudNotificationEventRow> = state.value
        ?.takeIf { owner != null && it.owner == owner }?.messages.orEmpty().map { it.row }
        .filter { owner != null && it.isUnreadMessageFor(owner) }
        .sortedWith(compareBy<CloudNotificationEventRow> { messageTimestamp(it) }.thenBy { it.id })

    @Synchronized fun restore(owner: String) {
        if (state.value?.owner == owner) return
        state.value = runCatching { prefs.getString("inbox", null)?.let { Json.decodeFromString<Entry>(it) } }
            .getOrNull()?.takeIf { it.owner == owner } ?: Entry(owner)
    }

    @Synchronized fun merge(owner: String, rows: List<CloudNotificationEventRow>, now: Long = System.currentTimeMillis()) {
        restore(owner)
        val old = state.value!!
        val messages = old.messages.associateBy { it.row.id }.toMutableMap()
        rows.forEach { row ->
            if (row.recipientUserId == owner && row.eventType == "TEAM_MESSAGE") {
                if (row.isUnreadMessageFor(owner) && row.id !in old.readIds) messages[row.id] = StoredMessage(row, now)
                else messages.remove(row.id)
            }
        }
        save(old.copy(messages = messages.values.toList()))
    }

    @Synchronized fun replace(owner: String, rows: List<CloudNotificationEventRow>, startedAt: Long) {
        restore(owner)
        val old = state.value!!
        val retained = old.messages.filter { it.receivedAt >= startedAt }.associateBy { it.row.id }.toMutableMap()
        rows.filter { it.isUnreadMessageFor(owner) && it.id !in old.readIds }
            .forEach { retained[it.id] = StoredMessage(it, startedAt) }
        save(old.copy(messages = retained.values.toList()))
    }

    @Synchronized fun markRead(owner: String, ids: Set<String>) {
        restore(owner)
        val old = state.value!!
        val owned = old.messages.filter { it.row.id in ids && it.row.isUnreadMessageFor(owner) }.map { it.row.id }.toSet()
        save(old.copy(messages = old.messages.filterNot { it.row.id in owned },
            readIds = (old.readIds.filterNot { it in owned } + owned).takeLast(5_000),
            pendingReads = old.pendingReads + owned))
    }

    fun pendingReads(owner: String): Set<String> = state.value?.takeIf { it.owner == owner }?.pendingReads.orEmpty()
    fun wasRead(owner: String, id: String): Boolean = state.value?.takeIf { it.owner == owner }?.readIds?.contains(id) == true
    @Synchronized fun acknowledge(owner: String, ids: Set<String>) {
        state.value?.takeIf { it.owner == owner }?.let { save(it.copy(pendingReads = it.pendingReads - ids)) }
    }
    @Synchronized fun clear() { state.value = null; prefs.edit().clear().apply() }
    private fun save(entry: Entry) { state.value = entry; prefs.edit().putString("inbox", Json.encodeToString(entry)).apply() }

    @Serializable internal data class StoredMessage(val row: CloudNotificationEventRow, val receivedAt: Long)
    @Serializable internal data class Entry(val owner: String, val messages: List<StoredMessage> = emptyList(),
        val readIds: List<String> = emptyList(), val pendingReads: Set<String> = emptySet())
    companion object {
        internal const val PREFS = "raad_team_messages"
        @Volatile private var instance: CloudTeamMessageCache? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: CloudTeamMessageCache(context).also { instance = it }
        }
    }
}

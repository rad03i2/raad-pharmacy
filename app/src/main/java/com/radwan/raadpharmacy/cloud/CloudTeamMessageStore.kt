package com.radwan.raadpharmacy.cloud

import android.content.Context
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

internal class CloudTeamMessageStore(context: Context) {
    private val appContext = context.applicationContext
    private val client = SupabaseProvider.client
    private val cache = CloudTeamMessageCache.get(appContext)
    private fun owner() = client.auth.currentSessionOrNull()?.user?.id
    fun current() = cache.current(owner())
    fun snapshots() = combine(cache.changes, client.auth.sessionStatus) { _, _ -> current() }

    suspend fun send(recipientId: String, messageId: String, text: String) {
        val body = text.trim()
        require(body.isNotEmpty() && body.length <= 500)
        client.auth.awaitInitialization()
        check(owner() != null)
        client.postgrest.rpc("send_team_message", buildJsonObject {
            put("target_user_id", recipientId)
            put("sender_device_id", CloudDeviceStore(appContext).deviceId())
            put("message_id", messageId)
            put("message_text", body)
        })
        CloudPushDispatcher.requestEvent(appContext, messageId)
    }

    suspend fun refresh() = cache.refreshMutex.withLock {
        client.auth.awaitInitialization()
        val userId = owner() ?: return@withLock
        cache.restore(userId)
        flushReads(userId)
        val startedAt = System.currentTimeMillis()
        val rows = mutableListOf<CloudNotificationEventRow>()
        var offset = 0L
        do {
            val page = client.from("notification_events").select {
                filter { eq("event_type", "TEAM_MESSAGE"); eq("recipient_user_id", userId); exact("message_read_at", null) }
                order("created_at", Order.ASCENDING); order("id", Order.ASCENDING)
                range(offset..offset + 99L)
            }.decodeList<CloudNotificationEventRow>()
            rows += page
            offset += page.size
        } while (page.size == 100)
        if (owner() == userId) cache.replace(userId, rows, startedAt)
    }

    suspend fun receive(row: CloudNotificationEventRow): Boolean {
        client.auth.awaitInitialization()
        val userId = owner() ?: return false
        if (!row.isUnreadMessageFor(userId)) return false
        cache.restore(userId)
        cache.merge(userId, listOf(row))
        return !cache.wasRead(userId, row.id)
    }

    suspend fun markRead(ids: Set<String>) {
        client.auth.awaitInitialization()
        val userId = owner() ?: return
        cache.markRead(userId, ids)
        flushReads(userId)
    }

    private suspend fun flushReads(userId: String) {
        for (batch in cache.pendingReads(userId).toList().chunked(100)) {
            if (owner() != userId) return
            try {
                client.postgrest.rpc("read_team_messages", buildJsonObject {
                    put("message_ids", buildJsonArray { batch.forEach { add(it) } })
                })
                if (owner() == userId) cache.acknowledge(userId, batch.toSet())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return /* Retry on the next sync; local read state stays durable. */ }
        }
    }
}

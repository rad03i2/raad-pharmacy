package com.radwan.raadpharmacy.cloud

import android.content.Context
import android.os.Build
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/** Registration is deliberately independent of ledger locks, uploads and FCM token availability. */
internal object CloudDeviceRegistration {
    private val mutex = Mutex()
    suspend fun register(context: Context, profile: CloudProfileRow, owner: String): String = mutex.withLock {
        val client = SupabaseProvider.client
        check(client.auth.currentSessionOrNull()?.user?.id == owner) { "AUTH_SESSION_CHANGED" }
        val store = CloudDeviceStore(context)
        store.savePushIdentity(owner, profile.pharmacyId)
        val id = store.deviceId()
        client.from("devices").upsert(CloudDeviceWrite(id, profile.pharmacyId, owner,
            listOf(Build.MANUFACTURER, Build.MODEL).filter(String::isNotBlank).joinToString(" ").ifBlank { "Android" },
            lastSeenAt = Instant.now().toString())) { onConflict = "id" }
        check(client.auth.currentSessionOrNull()?.user?.id == owner) { "AUTH_SESSION_CHANGED" }
        id
    }
}

internal class CloudMessagingSender(context: Context) {
    private val app = context.applicationContext
    private val client = SupabaseProvider.client
    private val pending = app.getSharedPreferences("raad_pending_team_alerts", Context.MODE_PRIVATE)

    suspend fun alert(recipient: String) {
        client.auth.awaitInitialization()
        val owner = checkNotNull(client.auth.currentSessionOrNull()?.user?.id) { "AUTH_SESSION_REQUIRED" }
        val key = "$owner:$recipient"
        val id = synchronized(alertLock) {
            pending.getString(key, null) ?: UUID.randomUUID().toString().also {
                check(pending.edit().putString(key, it).commit())
            }
        }
        send(recipient, id, null)
        synchronized(alertLock) { if (pending.getString(key, null) == id) pending.edit().remove(key).commit() }
    }

    suspend fun send(recipient: String, id: String, body: String?) {
        client.auth.awaitInitialization()
        val owner = checkNotNull(client.auth.currentSessionOrNull()?.user?.id) { "AUTH_SESSION_REQUIRED" }
        val type = if (body == null) "TEAM_ALERT" else "TEAM_MESSAGE"
        try {
            confirmedCloudSend(id, write = {
                suspend fun attempt(): String = withTimeout(20_000L) {
                    // Postgrest requireValidSession renews expired credentials before requests.
                    val profile = client.from("profiles").select { filter { eq("id", owner) } }.decodeSingle<CloudProfileRow>()
                    val device = CloudDeviceRegistration.register(app, profile, owner)
                    client.postgrest.rpc(if (body == null) "send_user_alert" else "send_team_message", buildJsonObject {
                        put("target_user_id", recipient); put("sender_device_id", device)
                        put(if (body == null) "alert_id" else "message_id", id)
                        body?.let { put("message_text", it) }
                    }).decodeAs<String>().also {
                        check(client.auth.currentSessionOrNull()?.user?.id == owner) { "AUTH_SESSION_CHANGED" }
                    }
                }
                try { attempt() }
                catch (failure: RestException) {
                    if (failure.statusCode != 401 || client.auth.currentSessionOrNull()?.user?.id != owner) throw failure
                    withTimeout(10_000L) { client.auth.refreshCurrentSession() }
                    check(client.auth.currentSessionOrNull()?.user?.id == owner) { "AUTH_SESSION_CHANGED" }
                    attempt() // exactly one renewal retry, preserving the same event UUID
                }
            }, saved = {
                withTimeout(5_000L) {
                    if (client.auth.currentSessionOrNull()?.user?.id != owner) false else {
                        val row = client.from("notification_events").select { filter { eq("id", id) } }
                            .decodeList<CloudNotificationEventRow>().singleOrNull()
                        row != null && row.actorUserId == owner && row.recipientUserId == recipient &&
                            row.eventType == type && (body == null || row.messageBody == body) &&
                            client.auth.currentSessionOrNull()?.user?.id == owner
                    }
                }
            })
            android.util.Log.i("RaadMessaging", "event_id=$id durable_confirmed_at=${System.currentTimeMillis()}")
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            CloudMessagingFeedback.record(type, failure)
            throw failure
        }
        // Failure to wake FCM never changes a confirmed database commit into a failed message.
        try { CloudPushDispatcher.requestEvent(app, id) }
        catch (failure: Exception) { CloudMessagingFeedback.record("dispatch_enqueue", failure) }
    }
    companion object { private val alertLock = Any() }
}

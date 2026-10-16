package com.radwan.raadpharmacy.cloud

import android.content.Context
import android.os.Build
import android.os.SystemClock
import io.github.jan.supabase.SupabaseClient
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
    private data class Receipt(val owner: String, val pharmacy: String, val device: String, val at: Long)
    @Volatile private var receipt: Receipt? = null

    fun invalidate() { receipt = null }

    suspend fun register(context: Context, profile: CloudProfileRow, owner: String,
        client: SupabaseClient = SupabaseProvider.client): String = mutex.withLock {
        registerLocked(context, profile, owner, client)
    }

    suspend fun ensure(context: Context, owner: String, force: Boolean = false,
        client: SupabaseClient = SupabaseProvider.client): String = mutex.withLock {
        check(client.auth.currentSessionOrNull()?.user?.id == owner) { "AUTH_SESSION_CHANGED" }
        val store = CloudDeviceStore(context)
        val saved = receipt
        if (!force && saved != null && saved.owner == owner && store.pushUserId() == owner &&
            saved.pharmacy == store.pushPharmacyId() && saved.device == store.deviceId() &&
            SystemClock.elapsedRealtime() - saved.at in 0L..60_000L) {
            return@withLock saved.device
        }
        val profile = client.from("profiles").select { filter { eq("id", owner) } }.decodeSingle<CloudProfileRow>()
        registerLocked(context, profile, owner, client)
    }

    private suspend fun registerLocked(context: Context, profile: CloudProfileRow, owner: String,
        client: SupabaseClient): String {
        check(client.auth.currentSessionOrNull()?.user?.id == owner) { "AUTH_SESSION_CHANGED" }
        check(profile.id == owner) { "AUTH_SESSION_CHANGED" }
        receipt = null
        val store = CloudDeviceStore(context)
        store.savePushIdentity(owner, profile.pharmacyId)
        val id = store.deviceId()
        client.from("devices").upsert(CloudDeviceWrite(id, profile.pharmacyId, owner,
            listOf(Build.MANUFACTURER, Build.MODEL).filter(String::isNotBlank).joinToString(" ").ifBlank { "Android" },
            lastSeenAt = Instant.now().toString())) { onConflict = "id" }
        check(client.auth.currentSessionOrNull()?.user?.id == owner) { "AUTH_SESSION_CHANGED" }
        // Only a server-confirmed registration is reusable, briefly and in this process.
        // Every message RPC still checks the current account, role and device on the server.
        receipt = Receipt(owner, profile.pharmacyId, id, SystemClock.elapsedRealtime())
        return id
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
                suspend fun attempt(forceRegistration: Boolean = false): String {
                    // Postgrest requireValidSession renews expired credentials before requests.
                    // Keep per-request deadlines: a cold registration needs several requests.
                    val device = CloudDeviceRegistration.ensure(app, owner, forceRegistration)
                    return client.postgrest.rpc(if (body == null) "send_user_alert" else "send_team_message", buildJsonObject {
                        put("target_user_id", recipient); put("sender_device_id", device)
                        put(if (body == null) "alert_id" else "message_id", id)
                        body?.let { put("message_text", it) }
                    }).decodeAs<String>().also {
                        check(client.auth.currentSessionOrNull()?.user?.id == owner) { "AUTH_SESSION_CHANGED" }
                    }
                }
                try { attempt() }
                catch (failure: RestException) {
                    if (client.auth.currentSessionOrNull()?.user?.id != owner) throw failure
                    when {
                        failure.statusCode == 401 -> {
                            withTimeout(10_000L) { client.auth.refreshCurrentSession() }
                            check(client.auth.currentSessionOrNull()?.user?.id == owner) { "AUTH_SESSION_CHANGED" }
                            attempt()
                        }
                        failure.message.orEmpty().let { "MESSAGE_DEVICE_INVALID" in it || "ALERT_DEVICE_INVALID" in it } -> {
                            CloudDeviceRegistration.invalidate()
                            attempt(forceRegistration = true)
                        }
                        else -> throw failure
                    } // At most one repair retry, always preserving the original event UUID.
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

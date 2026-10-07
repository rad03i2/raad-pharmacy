package com.radwan.raadpharmacy.cloud

import android.content.Context
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime

data class CloudTeamMember(
    val id: String,
    val displayName: String,
    val isCurrent: Boolean,
    val avatarFile: File?,
    val isOnline: Boolean,
    val lastSeenAt: Long?
)

data class CloudTeamSnapshot(
    val current: CloudTeamMember?,
    val others: List<CloudTeamMember>
)

class CloudTeamStore(context: Context) {
    private val appContext = context.applicationContext
    private val client = SupabaseProvider.client
    private val deviceStore = CloudDeviceStore(appContext)
    private val mediaStore = CloudMediaStore(appContext)

    suspend fun load(): CloudTeamSnapshot {
        client.auth.awaitInitialization()
        val userId = client.auth.currentSessionOrNull()?.user?.id
            ?: return CloudTeamSnapshot(null, emptyList())

        val (profiles, presence) = coroutineScope {
            val profilesRequest = async { client.from("profiles").select().decodeList<CloudTeamProfileRow>() }
            val presenceRequest = async { client.from("user_presence").select().decodeList<CloudPresenceRow>().associateBy { it.userId } }
            profilesRequest.await() to presenceRequest.await()
        }

        val visible = profiles.filter { profile ->
            profile.id == userId || (profile.role == "MANAGER" && !profile.isHidden)
        }

        val members = visible.map { profile ->
            val seen = presence[profile.id]
            val lastSeen = seen?.lastSeenAt?.let(::parseIso)
            val online = seen?.isOnline == true &&
                lastSeen != null &&
                System.currentTimeMillis() - lastSeen <= ONLINE_STALE_MS

            val avatar = runCatching {
                mediaStore.syncProfilePhoto(profile.id, profile.avatarPath)
            }.getOrElse {
                mediaStore.profilePhotoFile(profile.id).takeIf(File::isFile)
            }

            CloudTeamMember(
                id = profile.id,
                displayName = profile.displayName,
                isCurrent = profile.id == userId,
                avatarFile = avatar,
                isOnline = online,
                lastSeenAt = lastSeen
            )
        }

        return CloudTeamSnapshot(
            current = members.firstOrNull { it.isCurrent },
            others = members.filterNot { it.isCurrent }.sortedBy { it.displayName }
        )
    }

    suspend fun sendAlert(recipientId: String) {
        client.auth.awaitInitialization()
        check(client.auth.currentSessionOrNull() != null) { "يرجى تسجيل الدخول" }
        val alertId = UUID.randomUUID().toString()
        client.postgrest.rpc("send_user_alert", buildJsonObject {
            put("target_user_id", recipientId)
            put("sender_device_id", deviceStore.deviceId())
            put("alert_id", alertId)
        })
        CloudPushDispatcher.requestEvent(appContext, alertId)
    }

    suspend fun heartbeat(online: Boolean) {
        client.auth.awaitInitialization()
        val userId = client.auth.currentSessionOrNull()?.user?.id ?: return
        val profile = client.from("profiles")
            .select { filter { eq("id", userId) } }
            .decodeSingle<CloudProfileRow>()

        client.from("user_presence").upsert(
            CloudPresenceWrite(
                userId = userId,
                pharmacyId = profile.pharmacyId,
                deviceId = deviceStore.deviceId(),
                isOnline = online,
                lastSeenAt = Instant.now().toString()
            )
        ) { onConflict = "user_id" }
    }

    suspend fun uploadMyAvatar(bytes: ByteArray) {
        mediaStore.uploadMyAvatar(bytes)
    }

    private fun parseIso(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrNull()

    @Serializable
    private data class CloudPresenceWrite(
        @SerialName("user_id") val userId: String,
        @SerialName("pharmacy_id") val pharmacyId: String,
        @SerialName("device_id") val deviceId: String,
        @SerialName("is_online") val isOnline: Boolean,
        @SerialName("last_seen_at") val lastSeenAt: String
    )

    companion object {
        private const val ONLINE_STALE_MS = 70_000L
    }
}

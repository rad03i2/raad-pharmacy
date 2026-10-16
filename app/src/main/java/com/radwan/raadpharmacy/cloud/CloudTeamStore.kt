package com.radwan.raadpharmacy.cloud

import android.content.Context
import com.radwan.raadpharmacy.data.CustomerCreatorNameStore
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import android.os.SystemClock
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
    val lastSeenAt: Long?,
    val avatarRevision: Long = 0L
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

    private val cache = CloudTeamCache.get(appContext)

    fun cachedSnapshot(): CloudTeamSnapshot {
        val owner = client.auth.currentSessionOrNull()?.user?.id
        if (owner != null) cache.restore(owner)
        return cache.current(owner)
    }

    fun snapshots(): Flow<CloudTeamSnapshot> =
        combine(cache.changes, client.auth.sessionStatus, flow {
            emit(Unit)
            while (true) { delay(10_000L); emit(Unit) }
        }) { _, _, _ -> cachedSnapshot() }

    suspend fun load(force: Boolean = false): CloudTeamSnapshot {
        client.auth.awaitInitialization()
        return cache.refreshMutex.withLock {
            val userId = client.auth.currentSessionOrNull()?.user?.id
                ?: return@withLock CloudTeamSnapshot(null, emptyList())
            cache.restore(userId)
            val now = SystemClock.elapsedRealtime()
            if (!force && cache.refreshedOwner == userId && now - cache.refreshedAt < 5_000L) {
                return@withLock cachedSnapshot()
            }
            fun publish(snapshot: CloudTeamSnapshot) {
                // A request started under the previous session must never fill the new account's panel.
                if (client.auth.currentSessionOrNull()?.user?.id == userId) cache.publish(userId, snapshot)
            }
            coroutineScope {
                val profilesRequest = async {
                    withTimeout(15_000L) { client.from("profiles").select().decodeList<CloudTeamProfileRow>() }
                }
                val presenceRequest = async {
                    try { withTimeoutOrNull(8_000L) {
                        client.from("user_presence").select().decodeList<CloudPresenceRow>().associateBy { it.userId }
                    } ?: emptyMap() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyMap() }
                }
                val profiles = profilesRequest.await()
                val currentProfile = profiles.firstOrNull { it.id == userId }
                    ?: error("لم يتمكن الخادم من إظهار ملف المستخدم الحالي. سيتم الاحتفاظ بالأسماء المحفوظة.")
                currentProfile?.displayName?.let { CustomerCreatorNameStore.save(appContext, it) }
                val pharmacyId = currentProfile?.pharmacyId
                EntryActorStore.get(appContext).rememberProfiles(profiles.filter { it.pharmacyId == pharmacyId })
                val visible = profiles.filter {
                    it.pharmacyId == pharmacyId && (it.id == userId || !it.isHidden)
                }
                val previous = cachedSnapshot().let { listOfNotNull(it.current) + it.others }.associateBy { it.id }
                fun snapshot(members: List<CloudTeamMember>) = CloudTeamSnapshot(
                    members.firstOrNull { it.isCurrent }, members.filterNot { it.isCurrent }.sortedBy { it.displayName })
                val localMembers = visible.map { profile ->
                    val old = previous[profile.id]
                    val local = mediaStore.profilePhotoFile(profile.id).takeIf(File::isFile)
                    CloudTeamMember(profile.id, profile.displayName, profile.id == userId,
                        local, old?.isOnline == true, old?.lastSeenAt, local?.lastModified() ?: 0L)
                }
                // Names and saved photos appear before either presence or avatar downloads finish.
                publish(snapshot(localMembers))
                val avatarRequests = visible.map { profile -> async {
                    try { withTimeoutOrNull(8_000L) { mediaStore.syncProfilePhoto(profile.id, profile.avatarPath) }
                        ?: mediaStore.profilePhotoFile(profile.id).takeIf(File::isFile) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mediaStore.profilePhotoFile(profile.id).takeIf(File::isFile) }
                } }
                val presence = presenceRequest.await()
                val withPresence = localMembers.map { member ->
                    val seen = presence[member.id]
                    val lastSeen = seen?.lastSeenAt?.let(::parseIso) ?: member.lastSeenAt
                    member.copy(lastSeenAt = lastSeen, isOnline = seen?.isOnline == true &&
                        lastSeen != null && System.currentTimeMillis() - lastSeen in 0..CloudTeamCache.ONLINE_STALE_MS)
                }
                publish(snapshot(withPresence))
                val avatars = avatarRequests.awaitAll()
                publish(snapshot(withPresence.mapIndexed { index, member ->
                    val avatar = avatars[index]
                    member.copy(avatarFile = avatar, avatarRevision = avatar?.lastModified() ?: 0L)
                }))
                if (client.auth.currentSessionOrNull()?.user?.id == userId) {
                    cache.refreshedOwner = userId
                    cache.refreshedAt = SystemClock.elapsedRealtime()
                }
                cachedSnapshot()
            }
        }
    }

    suspend fun sendAlert(recipientId: String) {
        CloudMessagingSender(appContext).alert(recipientId)
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

    suspend fun uploadAvatar(userId: String, bytes: ByteArray) {
        mediaStore.uploadAvatar(userId, bytes)
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

}

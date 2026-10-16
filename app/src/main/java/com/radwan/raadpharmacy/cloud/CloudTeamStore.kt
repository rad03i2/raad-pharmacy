package com.radwan.raadpharmacy.cloud

import android.content.Context
import com.radwan.raadpharmacy.data.CustomerCreatorNameStore
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.decodeRecordOrNull
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

    private fun displayOwner(): String? {
        client.auth.currentSessionOrNull()?.user?.id?.let { return it }
        val status = client.auth.sessionStatus.value
        val offline = appContext.getSharedPreferences("raad_cloud_auth", Context.MODE_PRIVATE)
            .getBoolean("has_offline_session", false)
        return if (offline && (status is io.github.jan.supabase.auth.status.SessionStatus.Initializing ||
                status is io.github.jan.supabase.auth.status.SessionStatus.RefreshFailure)) deviceStore.pushUserId() else null
    }

    fun cachedSnapshot(): CloudTeamSnapshot {
        val owner = displayOwner()
        if (owner != null) cache.restore(owner)
        return cache.current(owner)
    }

    fun snapshots(): Flow<CloudTeamSnapshot> =
        combine(cache.changes, client.auth.sessionStatus, flow {
            emit(Unit)
            while (true) { delay(10_000L); emit(Unit) } // local online-state expiry; no network requests
        }) { _, _, _ -> cachedSnapshot() }

    suspend fun load(force: Boolean = false): CloudTeamSnapshot {
        client.auth.awaitInitialization()
        val userId = client.auth.currentSessionOrNull()?.user?.id ?: throw TeamSessionNotReady()
        return cache.refreshMutex.withLock {
            cache.restore(userId)
            val now = SystemClock.elapsedRealtime()
            if (!force && cache.refreshedOwner == userId && now - cache.refreshedAt < 5_000L) {
                return@withLock cachedSnapshot()
            }
            val startedGeneration = cache.generation
            val profiles = withTimeout(10_000L) {
                client.from("profiles").select().decodeList<CloudTeamProfileRow>()
            }
            check(client.auth.currentSessionOrNull()?.user?.id == userId) { "AUTH_SESSION_CHANGED" }
            val current = profiles.firstOrNull { it.id == userId } ?: error("TEAM_PROFILE_NOT_VISIBLE")
            val visible = profiles.filter { it.pharmacyId == current.pharmacyId && (it.id == userId || !it.isHidden) }
            val previous = cache.current(userId).let { listOfNotNull(it.current) + it.others }.associateBy { it.id }
            val members = visible.map { profile ->
                val old = previous[profile.id]
                val local = mediaStore.profilePhotoFile(profile.id).takeIf(File::isFile)
                CloudTeamMember(profile.id, profile.displayName, profile.id == userId,
                    local, old?.isOnline == true, old?.lastSeenAt, local?.lastModified() ?: 0L)
            }
            check(client.auth.currentSessionOrNull()?.user?.id == userId && cache.generation == startedGeneration) {
                "AUTH_SESSION_CHANGED"
            }
            cache.publish(userId, CloudTeamSnapshot(members.firstOrNull { it.isCurrent },
                members.filterNot { it.isCurrent }.sortedBy { it.displayName }))
            cache.refreshedOwner = userId
            cache.refreshedAt = SystemClock.elapsedRealtime()
            // All optional work follows publication, outside the names refresh mutex.
            CloudTeamRuntime.enrich(appContext, userId, cache.generation, visible)
            CustomerCreatorNameStore.save(appContext, current.displayName)
            EntryActorStore.get(appContext).rememberProfiles(visible)
            cache.current(userId)
        }
    }

    internal suspend fun enrich(owner: String, generation: Long, profiles: List<CloudTeamProfileRow>) =
        kotlinx.coroutines.supervisorScope {
            fun active() = client.auth.currentSessionOrNull()?.user?.id == owner && cache.generation == generation
            launch {
                try {
                    val presence = withTimeoutOrNull(4_000L) {
                        client.from("user_presence").select().decodeList<CloudPresenceRow>().associateBy { it.userId }
                    }.orEmpty()
                    if (active()) cache.enrich(owner, generation, presence = presence)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { CloudMessagingFeedback.record("team_presence", failure) }
            }
            profiles.forEach { profile -> launch {
                try {
                    val file = withTimeoutOrNull(6_000L) { mediaStore.syncProfilePhoto(profile.id, profile.avatarPath) }
                    if (active() && file != null) cache.enrich(owner, generation, avatars = mapOf(profile.id to file))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { CloudMessagingFeedback.record("team_avatar", failure) }
            } }
        }

    suspend fun sendAlert(recipientId: String) {
        CloudMessagingSender(appContext).alert(recipientId)
    }

    internal fun startRealtime(scope: kotlinx.coroutines.CoroutineScope): Boolean {
        val owner = client.auth.currentSessionOrNull()?.user?.id ?: return false
        val channel = client.channel("raad-team-presence-$owner")
        val changes = channel.postgresChangeFlow<io.github.jan.supabase.realtime.PostgresAction>("public") {
            table = "user_presence"
        }
        scope.launch {
            changes.collect { action ->
                if (client.auth.currentSessionOrNull()?.user?.id != owner) return@collect
                val row = when (action) {
                    is io.github.jan.supabase.realtime.PostgresAction.Insert -> action.decodeRecordOrNull<CloudPresenceRow>()
                    is io.github.jan.supabase.realtime.PostgresAction.Update -> action.decodeRecordOrNull<CloudPresenceRow>()
                    else -> null
                } ?: return@collect
                cache.enrich(owner, cache.generation, presence = mapOf(row.userId to row))
            }
        }
        scope.launch {
            channel.status.watchNotificationConnection(
                onConnected = { CloudTeamRuntime.request(appContext) },
                onLost = { CloudTeamRuntime.request(appContext) })
        }
        scope.launch {
            try { withTimeout(15_000L) { channel.subscribe(blockUntilSubscribed = true) } }
            catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                CloudMessagingFeedback.record("team_realtime", error)
                CloudTeamRuntime.request(appContext)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                CloudMessagingFeedback.record("team_realtime", error)
                CloudTeamRuntime.request(appContext)
            }
        }
        scope.launch {
            client.auth.sessionStatus.collect { status ->
                if (status is io.github.jan.supabase.auth.status.SessionStatus.Authenticated && status.session.user?.id == owner) {
                    try { channel.updateAuth(status.session.accessToken) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { CloudMessagingFeedback.record("team_realtime_auth", error) }
                }
            }
        }
        return true
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

    @Serializable
    private data class CloudPresenceWrite(
        @SerialName("user_id") val userId: String,
        @SerialName("pharmacy_id") val pharmacyId: String,
        @SerialName("device_id") val deviceId: String,
        @SerialName("is_online") val isOnline: Boolean,
        @SerialName("last_seen_at") val lastSeenAt: String
    )

}

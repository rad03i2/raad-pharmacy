package com.radwan.raadpharmacy.cloud

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One account's last successful team. Disk access is confined to IO callers. */
internal class CloudTeamCache(context: Context) {
    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val entry = MutableStateFlow<CachedTeam?>(null)
    val changes = entry.asStateFlow()
    val refreshMutex = Mutex()
    var refreshedOwner: String? = null
    var refreshedAt = 0L

    fun current(ownerId: String?, now: Long = System.currentTimeMillis()): CloudTeamSnapshot {
        val saved = entry.value?.takeIf { ownerId != null && it.ownerId == ownerId }
            ?: return CloudTeamSnapshot(null, emptyList())
        val members = saved.members.map { row ->
            CloudTeamMember(row.id, row.displayName, row.id == ownerId,
                row.avatarPath?.let(::File),
                row.isOnline && row.lastSeenAt != null && now - row.lastSeenAt in 0..ONLINE_STALE_MS,
                row.lastSeenAt, row.avatarRevision)
        }
        return CloudTeamSnapshot(members.firstOrNull { it.isCurrent },
            members.filterNot { it.isCurrent }.sortedBy { it.displayName })
    }

    @Synchronized fun restore(ownerId: String) {
        if (entry.value?.ownerId == ownerId) return
        val saved = runCatching {
            prefs.getString(KEY, null)?.let { Json.decodeFromString<CachedTeam>(it) }
        }.getOrNull()?.takeIf { it.ownerId == ownerId }
        saved?.members?.forEach { row ->
            CloudTeamAvatars.load(row.avatarPath?.let(::File), row.avatarRevision)
        }
        entry.value = saved
    }

    @Synchronized fun publish(ownerId: String, snapshot: CloudTeamSnapshot) {
        val members = listOfNotNull(snapshot.current) + snapshot.others
        members.forEach { CloudTeamAvatars.load(it.avatarFile, it.avatarRevision) }
        val saved = CachedTeam(ownerId, members.map {
            CachedMember(it.id, it.displayName, it.avatarFile?.absolutePath,
                it.avatarRevision, it.isOnline, it.lastSeenAt)
        })
        entry.value = saved
        prefs.edit().putString(KEY, Json.encodeToString(saved)).apply()
    }

    @Synchronized fun clear() {
        entry.value = null
        refreshedOwner = null
        refreshedAt = 0L
        prefs.edit().remove(KEY).apply()
        CloudTeamAvatars.clear()
    }

    @Serializable internal data class CachedTeam(val ownerId: String, val members: List<CachedMember>)
    @Serializable internal data class CachedMember(
        val id: String, val displayName: String, val avatarPath: String?,
        val avatarRevision: Long, val isOnline: Boolean, val lastSeenAt: Long?
    )

    companion object {
        internal const val PREFS = "raad_team_cache"
        private const val KEY = "team"
        internal const val ONLINE_STALE_MS = 70_000L
        @Volatile private var instance: CloudTeamCache? = null
        fun get(context: Context): CloudTeamCache = instance ?: synchronized(this) {
            instance ?: CloudTeamCache(context).also { instance = it }
        }
    }
}

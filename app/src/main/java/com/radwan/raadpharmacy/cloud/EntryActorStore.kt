package com.radwan.raadpharmacy.cloud

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Independent, persisted attribution metadata. This does not change Room, ledger
 * entries, balances, receipts, backup format or financial sync semantics.
 * The transaction creator is taken from Supabase's immutable created_by field.
 */
class EntryActorStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val authorIdsState = MutableStateFlow(readPrefixed(ACTOR_PREFIX))
    private val displayNamesState = MutableStateFlow(readPrefixed(NAME_PREFIX))
    val authorIds = authorIdsState.asStateFlow()
    val displayNames = displayNamesState.asStateFlow()

    private fun readPrefixed(prefix: String): Map<String, String> =
        prefs.all.entries.mapNotNull { (key, value) ->
            val name = key.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
            val text = value as? String
            if (name.isNullOrBlank() || text.isNullOrBlank()) null else name to text
        }.toMap()

    @Synchronized
    fun rememberActor(entryId: String, actorUserId: String?) {
        if (entryId.isBlank() || actorUserId.isNullOrBlank()) return
        if (authorIdsState.value[entryId] == actorUserId) return
        prefs.edit().putString(ACTOR_PREFIX + entryId, actorUserId).apply()
        authorIdsState.value = authorIdsState.value + (entryId to actorUserId)
    }

    @Synchronized
    fun rememberActors(rows: List<CloudTransactionRow>) {
        val additions = rows.mapNotNull { row ->
            val id = row.createdBy?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (authorIdsState.value[row.id] == id) null else row.id to id
        }.toMap()
        if (additions.isEmpty()) return
        val edit = prefs.edit()
        additions.forEach { (id, userId) -> edit.putString(ACTOR_PREFIX + id, userId) }
        edit.apply()
        authorIdsState.value = authorIdsState.value + additions
    }

    @Synchronized
    fun rememberProfiles(rows: List<CloudTeamProfileRow>) {
        val additions = rows.mapNotNull { profile ->
            val name = profile.displayName.trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            if (displayNamesState.value[profile.id] == name) null else profile.id to name
        }.toMap()
        if (additions.isEmpty()) return
        val edit = prefs.edit()
        additions.forEach { (id, name) -> edit.putString(NAME_PREFIX + id, name) }
        edit.apply()
        displayNamesState.value = displayNamesState.value + additions
    }

    companion object {
        private const val PREFS = "raad_entry_actor_attribution_v1"
        private const val ACTOR_PREFIX = "actor:"
        private const val NAME_PREFIX = "name:"
        @Volatile private var instance: EntryActorStore? = null

        fun get(context: Context): EntryActorStore = instance ?: synchronized(this) {
            instance ?: EntryActorStore(context).also { instance = it }
        }
    }
}

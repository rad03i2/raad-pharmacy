package com.radwan.raadpharmacy.cloud

import io.github.jan.supabase.realtime.RealtimeChannel
import kotlinx.coroutines.flow.Flow

/** Initial disconnected state is normal; every established connection needs a fresh snapshot. */
internal suspend fun Flow<RealtimeChannel.Status>.watchNotificationConnection(
    onConnected: () -> Unit, onLost: () -> Unit
) {
    var connected = false
    collect { status ->
        val next = status == RealtimeChannel.Status.SUBSCRIBED
        if (next && !connected) onConnected()
        if (!next && connected) onLost()
        connected = next
    }
}

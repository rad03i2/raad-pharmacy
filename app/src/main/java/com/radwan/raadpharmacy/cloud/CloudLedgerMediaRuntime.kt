package com.radwan.raadpharmacy.cloud

import android.content.Context
import com.radwan.raadpharmacy.data.PharmacyLedgerDatabase
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Optional photos never hold the financial snapshot/outbox lock. One merged queue per process. */
internal object CloudLedgerMediaRuntime {
    private data class Work(val app: Context, val owner: String, val row: CloudCustomerRow)
    private val pending = linkedMapOf<String, Work>()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for (signal in signals) {
                while (true) {
                    val work = synchronized(pending) {
                        pending.keys.firstOrNull()?.let { pending.remove(it) }
                    } ?: break
                    if (SupabaseProvider.client.auth.currentSessionOrNull()?.user?.id != work.owner ||
                        PharmacyLedgerDatabase.get(work.app).dao().restoreHold() == "1") continue
                    try {
                        withTimeout(8_000L) { CloudMediaStore(work.app).reconcileCustomerPhotos(listOf(work.row)) }
                    } catch (error: Exception) {
                        if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    }
                }
            }
        }
    }

    fun enqueue(context: Context, rows: List<CloudCustomerRow>) {
        val owner = SupabaseProvider.client.auth.currentSessionOrNull()?.user?.id ?: return
        synchronized(pending) {
            rows.filter { it.deletedAt == null }.forEach {
                pending["$owner:${it.id}"] = Work(context.applicationContext, owner, it)
            }
        }
        signals.trySend(Unit)
    }
}

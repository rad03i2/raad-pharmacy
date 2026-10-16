package com.radwan.raadpharmacy.cloud

import java.util.concurrent.atomic.AtomicBoolean

/** Optional presence cannot tear down healthy ledger/message subscriptions. */
internal class CloudRealtimeHealth {
    val ledger = AtomicBoolean(false)
    val messages = AtomicBoolean(false)
    fun needsRecovery(ledgerRequired: Boolean, messagesRequired: Boolean): Boolean =
        (ledgerRequired && !ledger.get()) || (messagesRequired && !messages.get())
    fun reset() { ledger.set(false); messages.set(false) }
}

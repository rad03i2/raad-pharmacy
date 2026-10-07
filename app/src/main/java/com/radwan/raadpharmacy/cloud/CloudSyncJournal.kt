package com.radwan.raadpharmacy.cloud

import android.content.Context

data class PendingCloudMutations(
    val customerUpserts: Set<String>,
    val customerDeletes: Set<String>,
    val transactionUpserts: Set<String>,
    val transactionDeletes: Set<String>
) {
    val isEmpty: Boolean
        get() = customerUpserts.isEmpty() &&
            customerDeletes.isEmpty() &&
            transactionUpserts.isEmpty() &&
            transactionDeletes.isEmpty()
}

class CloudSyncJournal(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun markCustomerUpsert(id: String) {
        mutate(CUSTOMER_UPSERTS) { it + id }
        mutate(CUSTOMER_DELETES) { it - id }
    }

    @Synchronized
    fun markCustomerDelete(id: String) {
        mutate(CUSTOMER_DELETES) { it + id }
        mutate(CUSTOMER_UPSERTS) { it - id }
    }

    @Synchronized
    fun markTransactionUpsert(id: String) {
        mutate(TRANSACTION_UPSERTS) { it + id }
        mutate(TRANSACTION_DELETES) { it - id }
    }

    @Synchronized
    fun markTransactionDelete(id: String) {
        mutate(TRANSACTION_DELETES) { it + id }
        mutate(TRANSACTION_UPSERTS) { it - id }
    }

    @Synchronized
    fun snapshot(): PendingCloudMutations = PendingCloudMutations(
        customerUpserts = read(CUSTOMER_UPSERTS),
        customerDeletes = read(CUSTOMER_DELETES),
        transactionUpserts = read(TRANSACTION_UPSERTS),
        transactionDeletes = read(TRANSACTION_DELETES)
    )

    @Synchronized
    fun clearCustomerUpsert(id: String) = mutate(CUSTOMER_UPSERTS) { it - id }

    @Synchronized
    fun clearCustomerDelete(id: String) = mutate(CUSTOMER_DELETES) { it - id }

    @Synchronized
    fun clearTransactionUpsert(id: String) = mutate(TRANSACTION_UPSERTS) { it - id }

    @Synchronized
    fun clearTransactionDelete(id: String) = mutate(TRANSACTION_DELETES) { it - id }

    private fun read(key: String): Set<String> =
        prefs.getStringSet(key, emptySet()).orEmpty().toSet()

    private fun mutate(key: String, block: (Set<String>) -> Set<String>) {
        prefs.edit().putStringSet(key, block(read(key)).toSet()).apply()
    }

    companion object {
        private const val PREFS_NAME = "raad_cloud_sync_journal"
        private const val CUSTOMER_UPSERTS = "customer_upserts"
        private const val CUSTOMER_DELETES = "customer_deletes"
        private const val TRANSACTION_UPSERTS = "transaction_upserts"
        private const val TRANSACTION_DELETES = "transaction_deletes"
    }
}

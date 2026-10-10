package com.radwan.raadpharmacy.cloud

import android.content.Context
import com.radwan.raadpharmacy.backup.BackupChangeEntity
import com.radwan.raadpharmacy.data.PharmacyLedgerDao
import com.radwan.raadpharmacy.data.PharmacyLedgerDatabase

data class PendingCloudMutations(
    val customerUpserts: Set<String>, val customerDeletes: Set<String>,
    val transactionUpserts: Set<String>, val transactionDeletes: Set<String>
) {
    val isEmpty: Boolean get() = customerUpserts.isEmpty() && customerDeletes.isEmpty() &&
        transactionUpserts.isEmpty() && transactionDeletes.isEmpty()
}

/** Upgrade adapter only. New mutations enqueue inside their financial Room transaction. */
class CloudSyncJournal(context: Context, private val dao: PharmacyLedgerDao = PharmacyLedgerDatabase.get(context).dao()) {
    private val prefs = context.applicationContext.getSharedPreferences("raad_cloud_sync_journal", Context.MODE_PRIVATE)

    suspend fun importLegacy() {
        if (dao.legacyCloudImported() == "1") return
        val rows = buildList {
            for (id in prefs.getStringSet("customer_upserts", emptySet()).orEmpty().toSet()) {
                val customer = dao.getCustomerById(id)
                add(CloudOutboxEntity.from(if (customer != null) BackupChangeEntity.customer(customer, "LOCAL")
                    else BackupChangeEntity.deleted("CUSTOMER", id, "LOCAL")))
            }
            for (id in prefs.getStringSet("transaction_upserts", emptySet()).orEmpty().toSet()) {
                val entry = dao.getEntryById(id)
                add(CloudOutboxEntity.from(if (entry != null) BackupChangeEntity.entry(entry, "LOCAL")
                    else BackupChangeEntity.deleted("ENTRY", id, "LOCAL")))
            }
            for (id in prefs.getStringSet("customer_deletes", emptySet()).orEmpty().toSet())
                add(CloudOutboxEntity.from(BackupChangeEntity.deleted("CUSTOMER", id, "LOCAL")))
            for (id in prefs.getStringSet("transaction_deletes", emptySet()).orEmpty().toSet())
                add(CloudOutboxEntity.from(BackupChangeEntity.deleted("ENTRY", id, "LOCAL")))
        }
        dao.importLegacyCloud(rows)
        // The SQLite marker survives death before the old preferences are cleared.
        prefs.edit().clear().commit()
    }

    suspend fun snapshot(): PendingCloudMutations = dao.cloudOutbox().pendingMutations()
}

package com.radwan.raadpharmacy.cloud

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.radwan.raadpharmacy.backup.BackupChangeEntity
import com.radwan.raadpharmacy.data.PharmacyLedgerDao
import kotlinx.coroutines.CancellationException

/** Independent of backup retention. Replacing a row gives it a new acknowledgement token. */
@Entity(tableName = "cloud_outbox")
data class CloudOutboxEntity(
    @PrimaryKey val key: String,
    val kind: String,
    val entityId: String,
    val action: String,
    val revision: String,
    val occurredAt: Long,
    val payload: String
) {
    companion object {
        fun from(change: BackupChangeEntity) = CloudOutboxEntity(
            "${change.kind}:${change.entityId}", change.kind, change.entityId, change.action,
            change.eventId, change.occurredAt, change.payload
        )
    }
}

object CloudOutboxMigration {
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("CREATE TABLE IF NOT EXISTS cloud_outbox (`key` TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, entityId TEXT NOT NULL, action TEXT NOT NULL, revision TEXT NOT NULL, occurredAt INTEGER NOT NULL, payload TEXT NOT NULL)")
            // Import only the known pending legacy queue before the first remote pull.
            // Backup history includes acknowledged changes and must never be replayed wholesale.
        }
    }
}

fun List<CloudOutboxEntity>.pendingMutations() = PendingCloudMutations(
    filter { it.kind == "CUSTOMER" && it.action == "UPSERT" }.mapTo(hashSetOf()) { it.entityId },
    filter { it.kind == "CUSTOMER" && it.action == "DELETE" }.mapTo(hashSetOf()) { it.entityId },
    filter { it.kind == "ENTRY" && it.action == "UPSERT" }.mapTo(hashSetOf()) { it.entityId },
    filter { it.kind == "ENTRY" && it.action == "DELETE" }.mapTo(hashSetOf()) { it.entityId }
)

/** A failed request or process death leaves the exact payload queued. Never acknowledge a newer edit. */
internal class DurableCloudDrain(private val dao: PharmacyLedgerDao,
    private val send: suspend (CloudOutboxEntity) -> Unit) {
    suspend fun flush() {
        var firstFailure: Exception? = null
        var failures = 0
        for (row in dao.cloudOutbox()) {
            if (dao.restoreHold() == "1") return
            try {
                send(row)
                dao.acknowledgeCloudMutation(row.key, row.revision)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (firstFailure == null) firstFailure = error
                if (++failures >= 3) break // Bound repeated failures during a shared outage.
            }
        }
        firstFailure?.let { throw it }
    }
}

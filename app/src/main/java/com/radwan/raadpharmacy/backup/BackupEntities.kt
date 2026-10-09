package com.radwan.raadpharmacy.backup

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.radwan.raadpharmacy.data.*
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

@Entity(tableName = "backup_changes", indices = [Index(value = ["eventId"], unique = true)])
data class BackupChangeEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val eventId: String = UUID.randomUUID().toString(),
    val kind: String,
    val entityId: String,
    val action: String,
    val origin: String,
    val occurredAt: Long = System.currentTimeMillis(),
    val payload: String
) {
    companion object {
        fun customer(row: CustomerEntity, origin: String) = BackupChangeEntity(kind = "CUSTOMER", entityId = row.id,
            action = "UPSERT", origin = origin, payload = BackupJson.encode(listOf(row.toModel()), emptyList(), "3.3.19.1"))
        fun entry(row: LedgerEntryEntity, origin: String) = BackupChangeEntity(kind = "ENTRY", entityId = row.id,
            action = "UPSERT", origin = origin, payload = BackupJson.encode(emptyList(), listOf(row.toModel()), "3.3.19.1"))
        fun deleted(kind: String, id: String, origin: String) = BackupChangeEntity(kind = kind, entityId = id,
            action = "DELETE", origin = origin, payload = "{}")
        fun photo(id: String, bytes: ByteArray?, origin: String) = BackupChangeEntity(kind = "PHOTO", entityId = id,
            action = if (bytes == null) "DELETE" else "UPSERT", origin = origin,
            payload = JSONObject().put("bytes", bytes?.let { Base64.getEncoder().encodeToString(it) }).toString())
    }
}

@Entity(tableName = "backup_destinations")
data class BackupDestinationEntity(
    @PrimaryKey val id: String,
    val treeUri: String? = null,
    val markerId: String = UUID.randomUUID().toString(),
    val chainId: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val cursor: Long = 0,
    val snapshotSequence: Long = 0,
    val lastFullAt: Long = 0,
    val lastChangeAt: Long = 0,
    val bytes: Long = 0,
    val error: String? = null
)
@Entity(tableName = "backup_photos")
data class BackupPhotoEntity(@PrimaryKey val customerId: String, val bytes: ByteArray)
@Entity(tableName = "backup_control")
data class BackupControlEntity(@PrimaryKey val id: String, val value: String)
data class BackupCapture(val customers: List<CustomerEntity>, val entries: List<LedgerEntryEntity>,
    val photos: List<BackupPhotoEntity>, val sequence: Long, val restoreHeld: Boolean)

object BackupMigration {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("CREATE TABLE IF NOT EXISTS backup_changes (sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, eventId TEXT NOT NULL, kind TEXT NOT NULL, entityId TEXT NOT NULL, action TEXT NOT NULL, origin TEXT NOT NULL, occurredAt INTEGER NOT NULL, payload TEXT NOT NULL)")
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_backup_changes_eventId ON backup_changes(eventId)")
            connection.execSQL("CREATE TABLE IF NOT EXISTS backup_destinations (id TEXT PRIMARY KEY NOT NULL, treeUri TEXT, markerId TEXT NOT NULL, chainId TEXT NOT NULL, enabled INTEGER NOT NULL, cursor INTEGER NOT NULL, snapshotSequence INTEGER NOT NULL, lastFullAt INTEGER NOT NULL, lastChangeAt INTEGER NOT NULL, bytes INTEGER NOT NULL, error TEXT)")
            connection.execSQL("CREATE TABLE IF NOT EXISTS backup_photos (customerId TEXT PRIMARY KEY NOT NULL, bytes BLOB NOT NULL)")
            connection.execSQL("CREATE TABLE IF NOT EXISTS backup_control (id TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        }
    }
}

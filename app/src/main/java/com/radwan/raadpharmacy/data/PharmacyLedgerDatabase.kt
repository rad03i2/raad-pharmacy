package com.radwan.raadpharmacy.data

import android.content.Context
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Transaction
import androidx.room3.Update
import com.radwan.raadpharmacy.backup.*
import com.radwan.raadpharmacy.cloud.CloudOutboxEntity
import com.radwan.raadpharmacy.cloud.CloudOutboxMigration
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "customers",
    indices = [
        Index(value = ["name"]),
        Index(value = ["area"]),
        Index(value = ["created_at"])
    ]
)
data class CustomerEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val phone: String?,
    val area: String,
    val address: String,
    @ColumnInfo(name = "opening_debt")
    val openingDebt: Long,
    val notes: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

@Entity(
    tableName = "ledger_entries",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customer_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["customer_id"]),
        Index(value = ["created_at"]),
        Index(value = ["type"])
    ]
)
data class LedgerEntryEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "customer_id")
    val customerId: String,
    val type: String,
    val amount: Long,
    val bottles: Int?,
    @ColumnInfo(name = "bottle_price")
    val bottlePrice: Long?,
    val details: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

@Dao
interface PharmacyLedgerDao {
    @Query("SELECT * FROM customers ORDER BY created_at DESC")
    suspend fun getCustomers(): List<CustomerEntity>

    @Query("SELECT * FROM customers ORDER BY created_at DESC")
    fun observeCustomers(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE id = :customerId LIMIT 1")
    suspend fun getCustomerById(customerId: String): CustomerEntity?

    @Query("SELECT * FROM ledger_entries ORDER BY created_at DESC")
    suspend fun getEntries(): List<LedgerEntryEntity>

    @Query("SELECT * FROM ledger_entries ORDER BY created_at DESC")
    fun observeEntries(): Flow<List<LedgerEntryEntity>>

    @Query("SELECT * FROM ledger_entries WHERE customer_id = :customerId ORDER BY created_at ASC")
    suspend fun getEntriesForCustomer(customerId: String): List<LedgerEntryEntity>

    @Query("SELECT * FROM ledger_entries WHERE id = :entryId LIMIT 1")
    suspend fun getEntryById(entryId: String): LedgerEntryEntity?

    @Query("SELECT COUNT(*) FROM customers")
    suspend fun customerCount(): Int

    @Query("SELECT COUNT(*) FROM ledger_entries")
    suspend fun entryCount(): Int

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN type = 'DEBT' THEN amount ELSE 0 END), 0) AS debts,
            COALESCE(SUM(CASE WHEN type = 'PAYMENT' THEN amount ELSE 0 END), 0) AS collections,
            COALESCE(SUM(CASE WHEN type = 'DEBT' THEN COALESCE(bottles, 0) ELSE 0 END), 0) AS bottles,
            (SELECT COUNT(*) FROM customers WHERE created_at >= :startMillis AND created_at < :endMillis) AS newCustomers
        FROM ledger_entries
        WHERE created_at >= :startMillis AND created_at < :endMillis
        """
    )
    suspend fun reportPeriodMetrics(
        startMillis: Long,
        endMillis: Long
    ): ReportPeriodMetrics

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN balance > 0 THEN balance ELSE 0 END), 0) AS totalDebt,
            COALESCE(SUM(CASE WHEN balance > 0 THEN 1 ELSE 0 END), 0) AS openAccounts,
            COUNT(*) AS totalCustomers
        FROM (
            SELECT
                c.id AS customerId,
                c.opening_debt +
                    COALESCE(SUM(
                        CASE
                            WHEN e.type = 'DEBT' THEN e.amount
                            WHEN e.type = 'PAYMENT' THEN -e.amount
                            ELSE 0
                        END
                    ), 0) AS balance
            FROM customers c
            LEFT JOIN ledger_entries e ON e.customer_id = c.id
            GROUP BY c.id
        )
        """
    )
    suspend fun currentDebtSummary(): CurrentDebtSummary

    @Query(
        """
        SELECT area, SUM(balance) AS balance
        FROM (
            SELECT
                CASE WHEN TRIM(c.area) = '' THEN 'غير محددة' ELSE c.area END AS area,
                CASE
                    WHEN c.opening_debt +
                        COALESCE(SUM(
                            CASE
                                WHEN e.type = 'DEBT' THEN e.amount
                                WHEN e.type = 'PAYMENT' THEN -e.amount
                                ELSE 0
                            END
                        ), 0) > 0
                    THEN c.opening_debt +
                        COALESCE(SUM(
                            CASE
                                WHEN e.type = 'DEBT' THEN e.amount
                                WHEN e.type = 'PAYMENT' THEN -e.amount
                                ELSE 0
                            END
                        ), 0)
                    ELSE 0
                END AS balance
            FROM customers c
            LEFT JOIN ledger_entries e ON e.customer_id = c.id
            GROUP BY c.id
        )
        WHERE balance > 0
        GROUP BY area
        ORDER BY balance DESC
        LIMIT :limit
        """
    )
    suspend fun topAreasByDebt(limit: Int): List<AreaDebtSummary>

    @Query(
        """
        SELECT
            c.id AS customerId,
            c.name AS name,
            CASE WHEN TRIM(c.area) = '' THEN 'غير محددة' ELSE c.area END AS area,
            c.opening_debt +
                COALESCE(SUM(
                    CASE
                        WHEN e.type = 'DEBT' THEN e.amount
                        WHEN e.type = 'PAYMENT' THEN -e.amount
                        ELSE 0
                    END
                ), 0) AS balance
        FROM customers c
        LEFT JOIN ledger_entries e ON e.customer_id = c.id
        GROUP BY c.id
        HAVING balance > 0
        ORDER BY balance DESC
        LIMIT :limit
        """
    )
    suspend fun topCustomersByDebt(limit: Int): List<CustomerDebtSummary>

    @Query(
        """
        SELECT
            strftime('%Y-%m-%d', created_at / 1000, 'unixepoch', 'localtime') AS dayKey,
            COALESCE(SUM(CASE WHEN type = 'DEBT' THEN amount ELSE 0 END), 0) AS debts,
            COALESCE(SUM(CASE WHEN type = 'PAYMENT' THEN amount ELSE 0 END), 0) AS collections
        FROM ledger_entries
        WHERE created_at >= :startMillis AND created_at < :endMillis
        GROUP BY dayKey
        ORDER BY dayKey ASC
        """
    )
    suspend fun dailyMovementSummary(
        startMillis: Long,
        endMillis: Long
    ): List<DailyMovementSummary>

    @Query(
        """
        SELECT * FROM ledger_entries
        WHERE customer_id = :customerId
          AND type = 'DEBT'
          AND amount = :amount
          AND created_at >= :cutoffMillis
        ORDER BY created_at DESC
        LIMIT 1
        """
    )
    suspend fun findRecentMatchingDebt(
        customerId: String,
        amount: Long,
        cutoffMillis: Long
    ): LedgerEntryEntity?

    @Query(
        """
        SELECT amount FROM ledger_entries
        WHERE customer_id = :customerId
          AND type = 'DEBT'
        ORDER BY created_at DESC
        LIMIT :limit
        """
    )
    suspend fun recentDebtAmounts(
        customerId: String,
        limit: Int
    ): List<Long>

    @Transaction
    suspend fun insertDebtProtected(
        entry: LedgerEntryEntity,
        duplicateCutoffMillis: Long,
        allowRecentDuplicate: Boolean
    ): LedgerEntryEntity? {
        if (!allowRecentDuplicate) {
            val duplicate = findRecentMatchingDebt(
                customerId = entry.customerId,
                amount = entry.amount,
                cutoffMillis = duplicateCutoffMillis
            )
            if (duplicate != null) return duplicate
        }

        insertEntry(entry)
        return null
    }

    // Only these transactional entrypoints are used by ledger, sync and restore.
    // Failure to persist the change journal rolls back the financial write as well.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCustomerRow(customer: CustomerEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntryRow(entry: LedgerEntryEntity)
    @Update
    suspend fun updateCustomerRow(customer: CustomerEntity)
    @Update
    suspend fun updateEntryRow(entry: LedgerEntryEntity)
    @Query("DELETE FROM ledger_entries WHERE id = :id")
    suspend fun deleteEntryRow(id: String)
    @Query("DELETE FROM customers WHERE id = :id")
    suspend fun deleteCustomerRow(id: String)

    @Insert
    suspend fun appendBackupChangeRow(change: BackupChangeEntity): Long
    @Transaction
    suspend fun appendBackupChange(change: BackupChangeEntity): Long {
        val seq = appendBackupChangeRow(change)
        saveBackupControl(BackupControlEntity("watermark", seq.toString()))
        if (change.origin == "LOCAL" && change.kind in listOf("CUSTOMER", "ENTRY")) {
            saveCloudMutation(CloudOutboxEntity.from(change))
        }
        return seq
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCloudMutation(row: CloudOutboxEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importCloudMutation(row: CloudOutboxEntity)
    @Query("SELECT * FROM cloud_outbox ORDER BY CASE WHEN kind = 'CUSTOMER' AND action = 'UPSERT' THEN 0 WHEN kind = 'ENTRY' AND action = 'UPSERT' THEN 1 WHEN kind = 'ENTRY' THEN 2 ELSE 3 END, occurredAt, `key`")
    suspend fun cloudOutbox(): List<CloudOutboxEntity>
    @Query("SELECT COUNT(*) FROM cloud_outbox")
    fun observeCloudPendingCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM cloud_outbox WHERE kind = :kind AND entityId = :id")
    suspend fun cloudPending(kind: String, id: String): Int
    @Query("DELETE FROM cloud_outbox WHERE `key` = :key AND revision = :revision")
    suspend fun acknowledgeCloudMutation(key: String, revision: String): Int
    @Query("DELETE FROM cloud_outbox")
    suspend fun clearCloudOutbox()
    @Query("SELECT value FROM backup_control WHERE id = 'cloud_legacy_imported'")
    suspend fun legacyCloudImported(): String?
    @Transaction
    suspend fun importLegacyCloud(rows: List<CloudOutboxEntity>) {
        if (legacyCloudImported() == "1") return
        rows.forEach { importCloudMutation(it) }
        saveBackupControl(BackupControlEntity("cloud_legacy_imported", "1"))
    }
    @Query("SELECT COUNT(*) FROM cloud_outbox o JOIN ledger_entries e ON o.entityId = e.id WHERE o.kind = 'ENTRY' AND e.customer_id = :id")
    suspend fun pendingCloudEntriesForCustomer(id: String): Int
    @Transaction
    suspend fun applyCloudCustomer(id: String, customer: CustomerEntity?) {
        if (restoreHold() == "1" || cloudPending("CUSTOMER", id) != 0) return
        if (customer != null) insertCustomer(customer, "REMOTE")
        else if (pendingCloudEntriesForCustomer(id) == 0) deleteCustomerById(id, "REMOTE")
    }
    @Transaction
    suspend fun applyCloudEntry(id: String, entry: LedgerEntryEntity?) {
        if (restoreHold() == "1" || cloudPending("ENTRY", id) != 0) return
        if (entry == null) deleteEntryById(id, "REMOTE")
        else if (getCustomerById(entry.customerId) != null) insertEntry(entry, "REMOTE")
    }
    @Transaction
    suspend fun applyCloudSnapshot(customers: List<CustomerEntity>, entries: List<LedgerEntryEntity>) {
        if (restoreHold() == "1") return
        val pending = cloudOutbox()
        val customerMap = customers.associateBy { it.id }.toMutableMap()
        val entryMap = entries.associateBy { it.id }.toMutableMap()
        for (row in pending) {
            if (row.kind == "CUSTOMER") {
                if (row.action == "DELETE") customerMap.remove(row.entityId)
                else getCustomerById(row.entityId)?.let { customerMap[it.id] = it }
            } else {
                if (row.action == "DELETE") entryMap.remove(row.entityId)
                else getEntryById(row.entityId)?.let {
                    entryMap[it.id] = it
                    getCustomerById(it.customerId)?.let { parent -> customerMap[parent.id] = parent }
                }
            }
        }
        replaceAll(customerMap.values.toList(), entryMap.values.filter { it.customerId in customerMap }, "REMOTE")
    }
    @Query("SELECT * FROM backup_changes WHERE sequence > :after ORDER BY sequence LIMIT :limit")
    suspend fun backupChanges(after: Long, limit: Int = 100): List<BackupChangeEntity>
    @Query("SELECT COALESCE(CAST((SELECT value FROM backup_control WHERE id = 'watermark') AS INTEGER), 0)")
    suspend fun latestBackupSequence(): Long
    @Query("SELECT COALESCE(CAST((SELECT value FROM backup_control WHERE id = 'watermark') AS INTEGER), 0)")
    fun observeBackupSequence(): Flow<Long>
    @Query("DELETE FROM backup_changes WHERE sequence <= :through")
    suspend fun pruneBackupChanges(through: Long)
    @Query("SELECT COUNT(*) FROM backup_changes WHERE sequence > :after")
    suspend fun pendingBackupCount(after: Long): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBackupDestination(destination: BackupDestinationEntity)
    @Query("SELECT * FROM backup_destinations")
    suspend fun backupDestinations(): List<BackupDestinationEntity>
    @Query("SELECT * FROM backup_destinations")
    fun observeBackupDestinations(): Flow<List<BackupDestinationEntity>>
    @Query("SELECT * FROM backup_photos")
    suspend fun backupPhotos(): List<BackupPhotoEntity>
    @Query("SELECT * FROM backup_photos WHERE customerId = :id")
    suspend fun backupPhoto(id: String): BackupPhotoEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBackupPhotoRow(photo: BackupPhotoEntity)
    @Query("DELETE FROM backup_photos WHERE customerId = :id")
    suspend fun deleteBackupPhotoRow(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBackupControl(control: BackupControlEntity)
    @Query("SELECT value FROM backup_control WHERE id = 'restore_hold'")
    suspend fun restoreHold(): String?

    @Transaction
    suspend fun insertCustomer(customer: CustomerEntity, origin: String = "LOCAL") {
        check(origin != "LOCAL" || restoreHold() != "1") { "التعديل معلق بعد الاستعادة. أكمل المصالحة من التخزين والنسخ الاحتياطي." }
        val old = getCustomerById(customer.id)
        if (old == customer) return
        if (old == null) insertCustomerRow(customer) else updateCustomerRow(customer)
        appendBackupChange(BackupChangeEntity.customer(customer, origin))
    }
    @Transaction
    suspend fun updateCustomer(customer: CustomerEntity, origin: String = "LOCAL") {
        if (getCustomerById(customer.id) != null) insertCustomer(customer, origin)
    }
    @Transaction
    suspend fun upsertCustomerPreservingEntries(customer: CustomerEntity, origin: String = "LOCAL") {
        insertCustomer(customer, origin)
    }
    @Transaction
    suspend fun insertEntry(entry: LedgerEntryEntity, origin: String = "LOCAL") {
        check(origin != "LOCAL" || restoreHold() != "1") { "التعديل معلق بعد الاستعادة. أكمل المصالحة من التخزين والنسخ الاحتياطي." }
        if (getEntryById(entry.id) == entry) return
        insertEntryRow(entry)
        appendBackupChange(BackupChangeEntity.entry(entry, origin))
    }
    @Transaction
    suspend fun updateEntry(entry: LedgerEntryEntity, origin: String = "LOCAL") {
        if (getEntryById(entry.id) != null) insertEntry(entry, origin)
    }
    @Transaction
    suspend fun insertCustomers(customers: List<CustomerEntity>, origin: String = "LOCAL") {
        customers.forEach { insertCustomer(it, origin) }
    }
    @Transaction
    suspend fun insertEntries(entries: List<LedgerEntryEntity>, origin: String = "LOCAL") {
        entries.forEach { insertEntry(it, origin) }
    }
    @Transaction
    suspend fun deleteEntryById(entryId: String, origin: String = "LOCAL") {
        check(origin != "LOCAL" || restoreHold() != "1") { "التعديل معلق بعد الاستعادة. أكمل المصالحة من التخزين والنسخ الاحتياطي." }
        if (getEntryById(entryId) == null) return
        deleteEntryRow(entryId)
        appendBackupChange(BackupChangeEntity.deleted("ENTRY", entryId, origin))
    }
    @Transaction
    suspend fun deleteCustomerById(customerId: String, origin: String = "LOCAL") {
        check(origin != "LOCAL" || restoreHold() != "1") { "التعديل معلق بعد الاستعادة. أكمل المصالحة من التخزين والنسخ الاحتياطي." }
        if (getCustomerById(customerId) == null) return
        getEntriesForCustomer(customerId).forEach { deleteEntryById(it.id, origin) }
        setBackupPhoto(customerId, null, origin)
        deleteCustomerRow(customerId)
        appendBackupChange(BackupChangeEntity.deleted("CUSTOMER", customerId, origin))
    }
    @Transaction
    suspend fun setBackupPhoto(id: String, bytes: ByteArray?, origin: String = "LOCAL") {
        check(origin != "LOCAL" || restoreHold() != "1") { "التعديل معلق بعد الاستعادة. أكمل المصالحة من التخزين والنسخ الاحتياطي." }
        if (bytes != null) {
            require(bytes.size <= com.radwan.raadpharmacy.customer.CustomerPhotoStore.MAX_BACKUP_PHOTO_BYTES) { "الصورة أكبر من الحجم الآمن للنسخ." }
            if (getCustomerById(id) == null) return
        }
        val old = backupPhoto(id)
        if (bytes == null) {
            if (old == null) return
            deleteBackupPhotoRow(id)
        } else {
            if (old != null && old.bytes.contentEquals(bytes)) return
            saveBackupPhotoRow(BackupPhotoEntity(id, bytes))
        }
        appendBackupChange(BackupChangeEntity.photo(id, bytes, origin))
    }
    @Transaction
    suspend fun clearEntries() { getEntries().forEach { deleteEntryById(it.id) } }
    @Transaction
    suspend fun clearCustomers() { getCustomers().forEach { deleteCustomerById(it.id) } }

    @Transaction
    suspend fun replaceAll(customers: List<CustomerEntity>, entries: List<LedgerEntryEntity>, origin: String = "LOCAL") {
        val ids = customers.mapTo(hashSetOf()) { it.id }
        val entryIds = entries.mapTo(hashSetOf()) { it.id }
        getEntries().filter { it.id !in entryIds }.forEach { deleteEntryById(it.id, origin) }
        getCustomers().filter { it.id !in ids }.forEach { deleteCustomerById(it.id, origin) }
        insertCustomers(customers, origin)
        insertEntries(entries, origin)
    }

    @Transaction
    suspend fun captureBackup(): BackupCapture = BackupCapture(
        getCustomers(), getEntries(), backupPhotos(), latestBackupSequence(), restoreHold() == "1"
    )

    @Transaction
    suspend fun completeReconciliation(customers: List<CustomerEntity>, entries: List<LedgerEntryEntity>) {
        clearCloudOutbox()
        replaceAll(customers, entries, "REMOTE")
        backupPhotos().forEach { setBackupPhoto(it.customerId, null, "REMOTE") }
        saveBackupControl(BackupControlEntity("restore_hold", "0"))
    }

    @Transaction
    suspend fun restoreLocal(customers: List<CustomerEntity>, entries: List<LedgerEntryEntity>, photos: List<BackupPhotoEntity>) {
        saveBackupControl(BackupControlEntity("restore_hold", "1"))
        // Existing requests were protected in the pre-restore safety archive. Restored
        // rows cannot impersonate them or be uploaded before explicit reconciliation.
        clearCloudOutbox()
        replaceAll(customers, entries, "RESTORE")
        backupPhotos().forEach { setBackupPhoto(it.customerId, null, "RESTORE") }
        photos.forEach { setBackupPhoto(it.customerId, it.bytes, "RESTORE") }
        // New lineage prevents future files being applied to pre-restore snapshots.
        backupDestinations().forEach {
            saveBackupDestination(it.copy(chainId = java.util.UUID.randomUUID().toString(), cursor = 0,
                snapshotSequence = 0, lastFullAt = 0, lastChangeAt = 0))
        }
    }

}

@Database(
    entities = [CustomerEntity::class, LedgerEntryEntity::class, BackupChangeEntity::class,
        BackupDestinationEntity::class, BackupPhotoEntity::class, BackupControlEntity::class,
        CloudOutboxEntity::class],
    version = 3,
    exportSchema = true
)
abstract class PharmacyLedgerDatabase : RoomDatabase() {
    abstract fun dao(): PharmacyLedgerDao

    companion object {
        @Volatile
        private var instance: PharmacyLedgerDatabase? = null

        fun get(context: Context): PharmacyLedgerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder<PharmacyLedgerDatabase>(
                    context.applicationContext,
                    "raad_pharmacy_ledger.db"
                )
                    .setDriver(DurableLedgerDriver())
                    .addMigrations(BackupMigration.MIGRATION_1_2, CloudOutboxMigration.MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}

fun Customer.toEntity(): CustomerEntity = CustomerEntity(
    id = id,
    name = name,
    phone = phone,
    area = area,
    address = address,
    openingDebt = openingDebt,
    notes = notes,
    createdAt = createdAt
)

fun CustomerEntity.toModel(): Customer = Customer(
    id = id,
    name = name,
    phone = phone,
    area = area,
    address = address,
    openingDebt = openingDebt,
    notes = notes,
    createdAt = createdAt
)

fun LedgerEntry.toEntity(): LedgerEntryEntity = LedgerEntryEntity(
    id = id,
    customerId = customerId,
    type = type.name,
    amount = amount,
    bottles = bottles,
    bottlePrice = bottlePrice,
    details = details,
    createdAt = createdAt
)

fun LedgerEntryEntity.toModel(): LedgerEntry = LedgerEntry(
    id = id,
    customerId = customerId,
    type = EntryType.valueOf(type),
    amount = amount,
    bottles = bottles,
    bottlePrice = bottlePrice,
    details = details,
    createdAt = createdAt
)

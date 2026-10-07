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
import androidx.sqlite.driver.AndroidSQLiteDriver
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomer(customer: CustomerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: LedgerEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomers(customers: List<CustomerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<LedgerEntryEntity>)

    @Update
    suspend fun updateCustomer(customer: CustomerEntity)

    @Transaction
    suspend fun upsertCustomerPreservingEntries(customer: CustomerEntity) {
        val existing = getCustomerById(customer.id)
        if (existing == null) insertCustomer(customer)
        else if (existing != customer) updateCustomer(customer)
    }

    @Update
    suspend fun updateEntry(entry: LedgerEntryEntity)

    @Query("DELETE FROM ledger_entries WHERE id = :entryId")
    suspend fun deleteEntryById(entryId: String)

    @Query("DELETE FROM customers WHERE id = :customerId")
    suspend fun deleteCustomerById(customerId: String)

    @Query("DELETE FROM ledger_entries")
    suspend fun clearEntries()

    @Query("DELETE FROM customers")
    suspend fun clearCustomers()

    @Transaction
    suspend fun replaceAll(
        customers: List<CustomerEntity>,
        entries: List<LedgerEntryEntity>
    ) {
        clearEntries()
        clearCustomers()
        if (customers.isNotEmpty()) insertCustomers(customers)
        if (entries.isNotEmpty()) insertEntries(entries)
    }
}

@Database(
    entities = [CustomerEntity::class, LedgerEntryEntity::class],
    version = 1,
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
                    .setDriver(AndroidSQLiteDriver())
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

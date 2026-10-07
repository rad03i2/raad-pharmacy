package com.radwan.raadpharmacy.data

import android.content.Context
import com.radwan.raadpharmacy.BuildConfig
import com.radwan.raadpharmacy.cloud.CloudSyncJournal
import com.radwan.raadpharmacy.cloud.CloudSyncRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class AppRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("raad_pharmacy_data", Context.MODE_PRIVATE)
    private val dao = PharmacyLedgerDatabase.get(appContext).dao()
    private val cloudJournal = CloudSyncJournal(appContext)

    @Volatile
    private var customersCache: List<Customer> = emptyList()

    @Volatile
    private var entriesCache: List<LedgerEntry> = emptyList()

    suspend fun initialize() {
        loadRoomOrMigrateLegacy()
        normalizeLegacyIdsForCloud()
        maybeCreateAutomaticBackup()
        CloudSyncRuntime.start(appContext)
    }

    fun observeCustomers(): Flow<List<Customer>> =
        dao.observeCustomers()
            .map { rows ->
                rows.map(CustomerEntity::toModel)
                    .also { customersCache = it }
            }
            .distinctUntilChanged()

    fun observeEntries(): Flow<List<LedgerEntry>> =
        dao.observeEntries()
            .map { rows ->
                rows.map(LedgerEntryEntity::toModel)
                    .also { entriesCache = it }
            }
            .distinctUntilChanged()

    fun customers(): List<Customer> = customersCache.toList()
    fun entries(): List<LedgerEntry> = entriesCache.toList()

    suspend fun addCustomer(
        name: String,
        phone: String?,
        area: String,
        address: String,
        openingDebt: Long,
        notes: String
    ): Customer {
        val customer = Customer(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            phone = phone?.trim()?.takeIf { it.isNotBlank() },
            area = area.trim(),
            address = address.trim(),
            openingDebt = openingDebt.coerceAtLeast(0L),
            notes = notes.trim()
        )
        dao.insertCustomer(customer.toEntity())
        customersCache = listOf(customer) + customersCache.filterNot { it.id == customer.id }
        cloudJournal.markCustomerUpsert(customer.id)
        CloudSyncRuntime.requestSync(appContext)
        maybeCreateAutomaticBackup()
        return customer
    }

    suspend fun updateCustomer(
        customerId: String,
        name: String,
        phone: String?,
        area: String,
        address: String,
        openingDebt: Long,
        notes: String
    ): MutationResult {
        val current = dao.getCustomerById(customerId)?.toModel()
            ?: return MutationResult(false, "تعذر العثور على الزبون.")
        if (name.isBlank()) return MutationResult(false, "اسم الزبون مطلوب.")
        if (openingDebt < 0L) return MutationResult(false, "الدين السابق لا يمكن أن يكون سالبًا.")

        val updated = current.copy(
            name = name.trim(),
            phone = phone?.trim()?.takeIf { it.isNotBlank() },
            area = area.trim(),
            address = address.trim(),
            openingDebt = openingDebt,
            notes = notes.trim()
        )

        val customerEntries = dao.getEntriesForCustomer(customerId).map(LedgerEntryEntity::toModel)
        if (!LedgerRules.isChronologicallyValid(updated, customerEntries)) {
            return MutationResult(
                false,
                "هذا التعديل يجعل أحد التحصيلات القديمة أكبر من الرصيد المتاح وقتها."
            )
        }

        dao.updateCustomer(updated.toEntity())
        customersCache = customersCache.map { if (it.id == customerId) updated else it }
        cloudJournal.markCustomerUpsert(customerId)
        CloudSyncRuntime.requestSync(appContext)
        maybeCreateAutomaticBackup()
        return MutationResult(true, "تم تحديث بيانات الزبون.")
    }

    suspend fun deleteCustomer(customerId: String): MutationResult {
        val customer = dao.getCustomerById(customerId)?.toModel()
            ?: return MutationResult(false, "تعذر العثور على الزبون.")
        val customerEntries = dao.getEntriesForCustomer(customerId).map(LedgerEntryEntity::toModel)

        if (customer.openingDebt != 0L) {
            return MutationResult(false, "لا يمكن حذف الزبون قبل تصفير الدين السابق.")
        }
        if (customerEntries.isNotEmpty()) {
            return MutationResult(false, "لا يمكن حذف الزبون لأن لديه حركات مالية محفوظة.")
        }

        dao.deleteCustomerById(customerId)
        customersCache = customersCache.filterNot { it.id == customerId }
        cloudJournal.markCustomerDelete(customerId)
        CloudSyncRuntime.requestSync(appContext)
        maybeCreateAutomaticBackup()
        return MutationResult(true, "تم حذف الزبون.")
    }

    suspend fun debtAnomalyWarning(
        customerId: String,
        candidateAmount: Long
    ): DebtAnomalyWarning? {
        require(candidateAmount > 0L)
        require(dao.getCustomerById(customerId) != null) { "Customer not found" }

        val historyCutoff = System.currentTimeMillis() - 180L * 24L * 60L * 60L * 1_000L
        val recent = dao.getEntriesForCustomer(customerId)
            .asSequence()
            .map(LedgerEntryEntity::toModel)
            .filter { entry ->
                entry.type == EntryType.DEBT &&
                    entry.createdAt >= historyCutoff &&
                    entry.amount > 0L
            }
            .sortedByDescending { it.createdAt }
            .take(DebtSafetyRules.ANOMALY_HISTORY_LIMIT)
            .map { it.amount }
            .toList()

        return DebtSafetyRules.anomalyWarning(recent, candidateAmount)
    }

    suspend fun addDebt(
        customerId: String,
        amount: Long,
        bottles: Int?,
        bottlePrice: Long?,
        details: String = "",
        allowRecentDuplicate: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): DebtCreateResult {
        require(amount > 0)
        require(dao.getCustomerById(customerId) != null) { "Customer not found" }

        val entry = LedgerEntry(
            id = UUID.randomUUID().toString(),
            customerId = customerId,
            type = EntryType.DEBT,
            amount = amount,
            bottles = bottles,
            bottlePrice = bottlePrice,
            details = details,
            createdAt = nowMillis
        )

        val previousDuplicate = dao.insertDebtProtected(
            entry = entry.toEntity(),
            duplicateCutoffMillis = nowMillis - DebtSafetyRules.DUPLICATE_WINDOW_MILLIS,
            allowRecentDuplicate = allowRecentDuplicate
        )

        if (previousDuplicate != null) {
            val previous = previousDuplicate.toModel()
            return DebtCreateResult.DuplicateDetected(
                previousEntry = previous,
                secondsAgo = ((nowMillis - previous.createdAt).coerceAtLeast(0L) / 1_000L)
            )
        }

        entriesCache = listOf(entry) + entriesCache.filterNot { it.id == entry.id }
        cloudJournal.markTransactionUpsert(entry.id)
        CloudSyncRuntime.requestSync(appContext)
        maybeCreateAutomaticBackup()
        return DebtCreateResult.Created(entry)
    }

    suspend fun addPayment(customerId: String, amount: Long): LedgerEntry {
        val customer = dao.getCustomerById(customerId)?.toModel()
            ?: error("Customer not found")
        val customerEntries = dao.getEntriesForCustomer(customerId).map(LedgerEntryEntity::toModel)
        val balance = customerBalance(customer, customerEntries)
        require(amount in 1..balance) { "Payment must be within current balance" }

        val entry = LedgerEntry(
            id = UUID.randomUUID().toString(),
            customerId = customerId,
            type = EntryType.PAYMENT,
            amount = amount
        )
        dao.insertEntry(entry.toEntity())
        entriesCache = listOf(entry) + entriesCache.filterNot { it.id == entry.id }
        cloudJournal.markTransactionUpsert(entry.id)
        CloudSyncRuntime.requestSync(appContext)
        maybeCreateAutomaticBackup()
        return entry
    }

    suspend fun updateEntry(
        entryId: String,
        amount: Long,
        bottles: Int?,
        bottlePrice: Long?,
        details: String
    ): MutationResult {
        val current = dao.getEntryById(entryId)?.toModel()
            ?: return MutationResult(false, "تعذر العثور على الحركة.")
        if (amount <= 0L) return MutationResult(false, "المبلغ يجب أن يكون أكبر من صفر.")

        val customer = dao.getCustomerById(current.customerId)?.toModel()
            ?: return MutationResult(false, "تعذر العثور على الزبون المرتبط بالحركة.")

        val updated = current.copy(
            amount = amount,
            bottles = if (current.type == EntryType.DEBT) bottles?.takeIf { it > 0 } else null,
            bottlePrice = if (current.type == EntryType.DEBT) bottlePrice?.takeIf { it > 0L } else null,
            details = if (current.type == EntryType.DEBT) details.trim() else current.details
        )

        val candidateEntries = dao.getEntriesForCustomer(current.customerId)
            .map(LedgerEntryEntity::toModel)
            .map { if (it.id == entryId) updated else it }

        if (!LedgerRules.isChronologicallyValid(customer, candidateEntries)) {
            return MutationResult(
                false,
                "لا يمكن حفظ التعديل لأنه يجعل تحصيلًا لاحقًا أكبر من الرصيد المتاح."
            )
        }

        dao.updateEntry(updated.toEntity())
        entriesCache = entriesCache.map { if (it.id == entryId) updated else it }
        cloudJournal.markTransactionUpsert(entryId)
        CloudSyncRuntime.requestSync(appContext)
        maybeCreateAutomaticBackup()
        return MutationResult(true, "تم تعديل الحركة.")
    }

    suspend fun deleteEntry(entryId: String): MutationResult {
        val current = dao.getEntryById(entryId)?.toModel()
            ?: return MutationResult(false, "تعذر العثور على الحركة.")
        val customer = dao.getCustomerById(current.customerId)?.toModel()
            ?: return MutationResult(false, "تعذر العثور على الزبون المرتبط بالحركة.")

        val candidateEntries = dao.getEntriesForCustomer(current.customerId)
            .map(LedgerEntryEntity::toModel)
            .filter { it.id != entryId }

        if (!LedgerRules.isChronologicallyValid(customer, candidateEntries)) {
            return MutationResult(
                false,
                "لا يمكن حذف هذه الحركة لأن حذفها يجعل سجل الحساب غير صالح محاسبيًا."
            )
        }

        dao.deleteEntryById(entryId)
        entriesCache = entriesCache.filterNot { it.id == entryId }
        cloudJournal.markTransactionDelete(entryId)
        CloudSyncRuntime.requestSync(appContext)
        maybeCreateAutomaticBackup()
        return MutationResult(true, "تم حذف الحركة.")
    }

    suspend fun resetDemoData() {
        val (customers, entries) = demoData()
        dao.replaceAll(
            customers = customers.map(Customer::toEntity),
            entries = entries.map(LedgerEntry::toEntity)
        )
        customersCache = customers
        entriesCache = entries
        prefs.edit().putBoolean(ROOM_INITIALIZED_KEY, true).apply()
        maybeCreateAutomaticBackup(force = true)
    }

    fun exportJson(): String = createBackupJson()

    fun createBackupJson(): String =
        BackupJson.encode(
            customers = customersCache,
            entries = entriesCache,
            appVersion = BuildConfig.VERSION_NAME
        )

    fun previewBackup(raw: String): BackupPreview =
        BackupValidator.preview(raw)

    suspend fun restoreBackup(raw: String): BackupRestoreResult {
        return runCatching {
            val payload = BackupValidator.parseValid(raw)
            val previousCustomerIds = dao.getCustomers().mapTo(hashSetOf()) { it.id }
            val previousEntryIds = dao.getEntries().mapTo(hashSetOf()) { it.id }

            writeRecoveryBackup(createBackupJson())

            dao.replaceAll(
                customers = payload.customers.map(Customer::toEntity),
                entries = payload.entries.map(LedgerEntry::toEntity)
            )

            customersCache = payload.customers
            entriesCache = payload.entries
            normalizeLegacyIdsForCloud()

            val restoredCustomers = dao.getCustomers()
            val restoredEntries = dao.getEntries()
            val restoredCustomerIds = restoredCustomers.mapTo(hashSetOf()) { it.id }
            val restoredEntryIds = restoredEntries.mapTo(hashSetOf()) { it.id }

            previousEntryIds
                .filter { it !in restoredEntryIds && it.isCloudUuid() }
                .forEach(cloudJournal::markTransactionDelete)
            previousCustomerIds
                .filter { it !in restoredCustomerIds && it.isCloudUuid() }
                .forEach(cloudJournal::markCustomerDelete)
            restoredCustomers.forEach { cloudJournal.markCustomerUpsert(it.id) }
            restoredEntries.forEach { cloudJournal.markTransactionUpsert(it.id) }
            CloudSyncRuntime.requestSync(appContext)

            val now = System.currentTimeMillis()
            prefs.edit()
                .putLong(LAST_RESTORE_AT_KEY, now)
                .putBoolean(ROOM_INITIALIZED_KEY, true)
                .apply()

            BackupRestoreResult(
                success = true,
                message = "تمت الاستعادة بنجاح، وتم حفظ نسخة أمان من البيانات السابقة وستتم مزامنتها سحابيًا.",
                customerCount = restoredCustomers.size,
                entryCount = restoredEntries.size
            )
        }.getOrElse {
            BackupRestoreResult(
                success = false,
                message = it.message ?: "تعذر استعادة النسخة الاحتياطية."
            )
        }
    }

    fun markManualBackupCreated(timestamp: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(LAST_MANUAL_BACKUP_AT_KEY, timestamp).apply()
    }

    fun lastBackupAt(): Long =
        maxOf(
            prefs.getLong(LAST_MANUAL_BACKUP_AT_KEY, 0L),
            prefs.getLong(LAST_AUTO_BACKUP_AT_KEY, 0L)
        )

    fun autoBackupInterval(): AutoBackupInterval =
        AutoBackupInterval.fromStorage(
            prefs.getString(AUTO_BACKUP_INTERVAL_KEY, AutoBackupInterval.WEEKLY.storageValue)
        )

    suspend fun setAutoBackupInterval(interval: AutoBackupInterval) {
        prefs.edit()
            .putString(AUTO_BACKUP_INTERVAL_KEY, interval.storageValue)
            .apply()
        maybeCreateAutomaticBackup(force = interval != AutoBackupInterval.OFF)
    }

    private suspend fun maybeCreateAutomaticBackup(force: Boolean = false) {
        val interval = autoBackupInterval()
        if (interval == AutoBackupInterval.OFF) return

        val now = System.currentTimeMillis()
        val last = prefs.getLong(LAST_AUTO_BACKUP_AT_KEY, 0L)
        val dueAfter = when (interval) {
            AutoBackupInterval.DAILY -> 24L * 60L * 60L * 1000L
            AutoBackupInterval.WEEKLY -> 7L * 24L * 60L * 60L * 1000L
            AutoBackupInterval.OFF -> Long.MAX_VALUE
        }

        if (!force && last > 0L && now - last < dueAfter) return

        runCatching {
            withContext(Dispatchers.IO) {
                val dir = File(appContext.filesDir, "auto_backups").apply { mkdirs() }
                val file = File(dir, "auto-backup-" + now + ".json")
                file.writeText(createBackupJson())
                trimBackupDirectory(dir, keep = 7)
                prefs.edit().putLong(LAST_AUTO_BACKUP_AT_KEY, now).apply()
            }
        }
    }

    private suspend fun writeRecoveryBackup(raw: String) {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            val dir = File(appContext.filesDir, "restore_recovery").apply { mkdirs() }
            File(dir, "before-restore-" + now + ".json").writeText(raw)
            trimBackupDirectory(dir, keep = 5)
        }
    }

    private fun trimBackupDirectory(dir: File, keep: Int) {
        dir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep)
            ?.forEach { runCatching { it.delete() } }
    }

    private suspend fun loadRoomOrMigrateLegacy() {
        val dbCustomers = dao.getCustomers().map(CustomerEntity::toModel)
        val dbEntries = dao.getEntries().map(LedgerEntryEntity::toModel)
        val alreadyInitialized = prefs.getBoolean(ROOM_INITIALIZED_KEY, false)

        if (dbCustomers.isNotEmpty() || dbEntries.isNotEmpty()) {
            customersCache = dbCustomers
            entriesCache = dbEntries
            if (!alreadyInitialized) {
                prefs.edit().putBoolean(ROOM_INITIALIZED_KEY, true).apply()
            }
            return
        }

        if (alreadyInitialized) {
            customersCache = emptyList()
            entriesCache = emptyList()
            return
        }

        val legacyCustomers = parseCustomers(prefs.getString("customers", null))
        val knownCustomerIds = legacyCustomers.mapTo(hashSetOf()) { it.id }
        val legacyEntries = parseEntries(prefs.getString("entries", null))
            .filter { it.customerId in knownCustomerIds }

        val source = if (legacyCustomers.isNotEmpty()) {
            legacyCustomers to legacyEntries
        } else {
            emptyList<Customer>() to emptyList<LedgerEntry>()
        }

        dao.replaceAll(
            customers = source.first.map(Customer::toEntity),
            entries = source.second.map(LedgerEntry::toEntity)
        )
        customersCache = source.first
        entriesCache = source.second
        prefs.edit().putBoolean(ROOM_INITIALIZED_KEY, true).apply()
    }


    private suspend fun normalizeLegacyIdsForCloud() {
        val currentCustomers = dao.getCustomers().map(CustomerEntity::toModel)
        val currentEntries = dao.getEntries().map(LedgerEntryEntity::toModel)
        if (currentCustomers.isEmpty() && currentEntries.isEmpty()) return

        val customerIds = currentCustomers.associate { customer ->
            customer.id to customer.id.toCloudUuid()
        }
        val normalizedCustomers = currentCustomers.map { customer ->
            customer.copy(id = customerIds.getValue(customer.id))
        }
        val normalizedEntries = currentEntries
            .filter { it.customerId in customerIds }
            .map { entry ->
                entry.copy(
                    id = entry.id.toCloudUuid(),
                    customerId = customerIds.getValue(entry.customerId)
                )
            }

        val changed = normalizedCustomers.zip(currentCustomers).any { (a, b) -> a.id != b.id } ||
            normalizedEntries.zip(currentEntries).any { (a, b) ->
                a.id != b.id || a.customerId != b.customerId
            }

        if (changed) {
            dao.replaceAll(
                customers = normalizedCustomers.map(Customer::toEntity),
                entries = normalizedEntries.map(LedgerEntry::toEntity)
            )
        }

        customersCache = normalizedCustomers
        entriesCache = normalizedEntries
    }

    private fun String.toCloudUuid(): String =
        runCatching { UUID.fromString(this).toString() }
            .getOrElse { UUID.randomUUID().toString() }

    private fun String.isCloudUuid(): Boolean =
        runCatching { UUID.fromString(this) }.isSuccess


    private fun customersToJson(): JSONArray = JSONArray().apply {
        customersCache.forEach { customer ->
            put(JSONObject().apply {
                put("id", customer.id)
                put("name", customer.name)
                put("phone", customer.phone ?: "")
                put("area", customer.area)
                put("address", customer.address)
                put("openingDebt", customer.openingDebt)
                put("notes", customer.notes)
                put("createdAt", customer.createdAt)
            })
        }
    }

    private fun entriesToJson(): JSONArray = JSONArray().apply {
        entriesCache.forEach { entry ->
            put(JSONObject().apply {
                put("id", entry.id)
                put("customerId", entry.customerId)
                put("type", entry.type.name)
                put("amount", entry.amount)
                put("bottles", entry.bottles ?: JSONObject.NULL)
                put("bottlePrice", entry.bottlePrice ?: JSONObject.NULL)
                put("details", entry.details)
                put("createdAt", entry.createdAt)
            })
        }
    }

    private fun parseCustomers(raw: String?): List<Customer> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        Customer(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            phone = item.optString("phone").takeIf { it.isNotBlank() },
                            area = item.optString("area"),
                            address = item.optString("address"),
                            openingDebt = item.optLong("openingDebt"),
                            notes = item.optString("notes"),
                            createdAt = item.optLong("createdAt")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseEntries(raw: String?): List<LedgerEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        LedgerEntry(
                            id = item.getString("id"),
                            customerId = item.getString("customerId"),
                            type = EntryType.valueOf(item.getString("type")),
                            amount = item.getLong("amount"),
                            bottles = if (item.isNull("bottles")) null else item.getInt("bottles"),
                            bottlePrice = if (item.isNull("bottlePrice")) null else item.getLong("bottlePrice"),
                            details = item.optString("details"),
                            createdAt = item.getLong("createdAt")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun demoData(): Pair<List<Customer>, List<LedgerEntry>> {
        val now = System.currentTimeMillis()
        val day = 24L * 60L * 60L * 1000L

        val customers = listOf(
            Customer("c1", "أحمد محمود", "07701234567", "حي النور", "قرب جامع النور", 25_000, createdAt = now - 90 * day),
            Customer("c2", "علي حسن", "07511223344", "حي الجامعة", "الشارع الرئيسي", 0, createdAt = now - 70 * day),
            Customer("c3", "محمد جاسم", null, "حي الزهور", "قرب المدرسة", 50_000, createdAt = now - 45 * day),
            Customer("c4", "مصطفى كريم", "07805556677", "حي النور", "الفرع الثاني", 0, createdAt = now - 30 * day),
            Customer("c5", "حيدر عبد الله", "07718889900", "حي الجامعة", "", 100_000, createdAt = now - 120 * day),
            Customer("c6", "عمر سالم", "07509998877", "حي السلام", "مقابل السوق", 0, createdAt = now - 20 * day)
        )

        val entries = listOf(
            LedgerEntry("e1", "c1", EntryType.DEBT, 35_000, details = "أدوية", createdAt = now - 2 * 60 * 60 * 1000L),
            LedgerEntry("e2", "c2", EntryType.DEBT, 75_000, details = "مشتريات صيدلية", createdAt = now - 3 * 60 * 60 * 1000L),
            LedgerEntry("e3", "c2", EntryType.PAYMENT, 25_000, createdAt = now - 90 * 60 * 1000L),
            LedgerEntry("e4", "c3", EntryType.DEBT, 25_000, details = "أدوية", createdAt = now - 5 * day),
            LedgerEntry("e5", "c4", EntryType.DEBT, 50_000, details = "مستلزمات صيدلية", createdAt = now - day),
            LedgerEntry("e6", "c4", EntryType.PAYMENT, 50_000, createdAt = now - 6 * 60 * 60 * 1000L),
            LedgerEntry("e7", "c5", EntryType.DEBT, 150_000, details = "أدوية", createdAt = now - 35 * day),
            LedgerEntry("e8", "c5", EntryType.PAYMENT, 50_000, createdAt = now - 31 * day),
            LedgerEntry("e9", "c6", EntryType.DEBT, 25_000, details = "مشتريات صيدلية", createdAt = now - 4 * 60 * 60 * 1000L),
            LedgerEntry("e10", "c6", EntryType.PAYMENT, 10_000, createdAt = now - 30 * 60 * 1000L)
        )

        return customers to entries
    }

    companion object {
        private const val ROOM_INITIALIZED_KEY = "room_initialized_v1"
        private const val LAST_MANUAL_BACKUP_AT_KEY = "last_manual_backup_at"
        private const val LAST_AUTO_BACKUP_AT_KEY = "last_auto_backup_at"
        private const val LAST_RESTORE_AT_KEY = "last_restore_at"
        private const val AUTO_BACKUP_INTERVAL_KEY = "auto_backup_interval"
    }
}

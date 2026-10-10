package com.radwan.raadpharmacy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.radwan.raadpharmacy.customer.CustomerPhotoStore
import com.radwan.raadpharmacy.cloud.EntryActorStore
import com.radwan.raadpharmacy.cloud.SupabaseProvider
import io.github.jan.supabase.auth.auth
import com.radwan.raadpharmacy.data.AppRepository
import com.radwan.raadpharmacy.data.AutoBackupInterval
import com.radwan.raadpharmacy.data.BackupPreview
import com.radwan.raadpharmacy.data.BackupRestoreResult
import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.DebtAnomalyWarning
import com.radwan.raadpharmacy.data.DebtCreateResult
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.data.LedgerEntry
import com.radwan.raadpharmacy.data.MutationResult
import com.radwan.raadpharmacy.data.AdvancedReportSnapshot
import com.radwan.raadpharmacy.data.ReportPeriodV11
import com.radwan.raadpharmacy.data.ReportRepository
import com.radwan.raadpharmacy.security.AppSecurityStore
import com.radwan.raadpharmacy.security.SecurityMutationResult
import com.radwan.raadpharmacy.security.SecurityState
import com.radwan.raadpharmacy.notifications.FinancialOperationFeedback
import com.radwan.raadpharmacy.notifications.FinancialOperationReceipt
import com.radwan.raadpharmacy.notifications.FinancialFeedbackSettings
import com.radwan.raadpharmacy.notifications.FinancialFeedbackStore
import com.radwan.raadpharmacy.notifications.NotificationSoundPreset
import com.radwan.raadpharmacy.notifications.OperationSoundPreset
import com.radwan.raadpharmacy.notifications.ReminderFrequency
import com.radwan.raadpharmacy.notifications.ReminderScheduler
import com.radwan.raadpharmacy.notifications.ReminderSettings
import com.radwan.raadpharmacy.notifications.ReminderStore
import com.radwan.raadpharmacy.ui.theme.AppFontPreset
import com.radwan.raadpharmacy.ui.theme.TypographyPreferences
import com.radwan.raadpharmacy.ui.theme.TypographySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PharmacyLedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val repository = AppRepository(application)
    private val security = AppSecurityStore(application)
    private val reminders = ReminderStore(application)
    private val reportRepository = ReportRepository(application)
    private val financialFeedback = FinancialFeedbackStore(application)
    private val customerPhotos = CustomerPhotoStore(application)
    private val typographyPreferences = TypographyPreferences(application)

    private val _typographySettings = MutableStateFlow(typographyPreferences.state())
    val typographySettings: StateFlow<TypographySettings> =
        _typographySettings.asStateFlow()

    private val _securityState = MutableStateFlow(security.state())
    val securityState: StateFlow<SecurityState> = _securityState.asStateFlow()

    private val _isUnlocked = MutableStateFlow(!security.isPinEnabled())
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _reminderSettings = MutableStateFlow(reminders.state())
    val reminderSettings: StateFlow<ReminderSettings> = _reminderSettings.asStateFlow()

    private val _advancedReport = MutableStateFlow(
        AdvancedReportSnapshot(period = ReportPeriodV11.MONTH, loading = true)
    )
    val advancedReport: StateFlow<AdvancedReportSnapshot> = _advancedReport.asStateFlow()

    private val _customers = MutableStateFlow<List<Customer>>(emptyList())
    val customers: StateFlow<List<Customer>> = _customers.asStateFlow()

    private val _entries = MutableStateFlow<List<LedgerEntry>>(emptyList())
    val entries: StateFlow<List<LedgerEntry>> = _entries.asStateFlow()

    private val writeMutex = Mutex()

    // Expensive ledger calculations are indexed once after every write instead of
    // re-filtering the entire history on every card recomposition.
    private var balanceByCustomer: Map<String, Long> = emptyMap()
    private var entriesByCustomer: Map<String, List<LedgerEntry>> = emptyMap()
    private var lastEntryByCustomer: Map<String, LedgerEntry> = emptyMap()
    private var lastPaymentByCustomer: Map<String, LedgerEntry> = emptyMap()
    private var todayEntriesCache: List<LedgerEntry> = emptyList()
    private var todayDebtEntriesCache: List<LedgerEntry> = emptyList()
    private var todayPaymentEntriesCache: List<LedgerEntry> = emptyList()
    private var todayDebtsCache: Long = 0L
    private var todayCollectionsCache: Long = 0L
    private var topDebtorsCache: List<Customer> = emptyList()

    init {
        ReminderScheduler.apply(app, _reminderSettings.value)
        viewModelScope.launch {
            repository.initialize()
            loadAdvancedReport(ReportPeriodV11.MONTH)
            combine(
                repository.observeCustomers(),
                repository.observeEntries()
            ) { customers, entries ->
                customers to entries
            }.collect { (customers, entries) ->
                writeMutex.withLock {
                    val indexes = withContext(Dispatchers.Default) { buildIndexes(customers, entries) }
                    applyIndexes(indexes)
                    _customers.value = customers
                    _entries.value = entries
                }
            }
        }
    }

    fun setAppFont(font: AppFontPreset) {
        _typographySettings.value = typographyPreferences.setFont(font)
    }

    fun setTextScale(scale: Float) {
        _typographySettings.value = typographyPreferences.setScale(scale)
    }

    fun resetTypography() {
        _typographySettings.value = typographyPreferences.reset()
    }

    fun customer(id: String): Customer? = _customers.value.firstOrNull { it.id == id }

    fun balance(customer: Customer): Long =
        balanceByCustomer[customer.id] ?: customer.openingDebt.coerceAtLeast(0L)

    fun entriesFor(customerId: String): List<LedgerEntry> =
        entriesByCustomer[customerId].orEmpty()

    private fun rememberLocalActor(entryId: String) {
        runCatching {
            EntryActorStore.get(app).rememberActor(entryId, SupabaseProvider.client.auth.currentSessionOrNull()?.user?.id)
        }.onFailure { android.util.Log.w("PharmacyLedger", "Author metadata deferred", it) }
    }

    suspend fun addCustomer(
        name: String,
        phone: String?,
        area: String,
        address: String,
        openingDebt: Long,
        notes: String
    ): Customer = writeMutex.withLock {
        val customer = repository.addCustomer(name, phone, area, address, openingDebt, notes)
        _customers.value = listOf(customer) + _customers.value.filterNot { it.id == customer.id }
        rebuildIndexes()
        loadAdvancedReport(_advancedReport.value.period)
        customer
    }

    suspend fun updateCustomer(
        customerId: String,
        name: String,
        phone: String?,
        area: String,
        address: String,
        openingDebt: Long,
        notes: String
    ): MutationResult = writeMutex.withLock {
        val result = repository.updateCustomer(
            customerId, name, phone, area, address, openingDebt, notes
        )
        if (result.success) {
            val current = _customers.value.firstOrNull { it.id == customerId }
            if (current != null) {
                val updated = current.copy(
                    name = name.trim(),
                    phone = phone?.trim()?.takeIf { it.isNotBlank() },
                    area = area.trim(),
                    address = address.trim(),
                    openingDebt = openingDebt,
                    notes = notes.trim()
                )
                _customers.value = _customers.value.map {
                    if (it.id == customerId) updated else it
                }
                rebuildIndexes()
            }
            loadAdvancedReport(_advancedReport.value.period)
        }
        result
    }

    suspend fun deleteCustomer(customerId: String): MutationResult = writeMutex.withLock {
        val result = repository.deleteCustomer(customerId)
        if (result.success) {
            customerPhotos.remove(customerId)
            _customers.value = _customers.value.filterNot { it.id == customerId }
            rebuildIndexes()
            loadAdvancedReport(_advancedReport.value.period)
        }
        result
    }

    suspend fun debtAnomalyWarning(
        customerId: String,
        amount: Long
    ): DebtAnomalyWarning? =
        repository.debtAnomalyWarning(customerId, amount)

    suspend fun addDebt(
        customerId: String,
        amount: Long,
        bottles: Int? = null,
        bottlePrice: Long? = null,
        details: String = "",
        allowRecentDuplicate: Boolean = false
    ): DebtCreateResult = writeMutex.withLock {
        val result = repository.addDebt(
            customerId = customerId,
            amount = amount,
            bottles = bottles,
            bottlePrice = bottlePrice,
            details = details.trim(),
            allowRecentDuplicate = allowRecentDuplicate
        )

        if (result is DebtCreateResult.Created) {
            val entry = result.entry
            rememberLocalActor(entry.id)
            _entries.value = listOf(entry) + _entries.value.filterNot { it.id == entry.id }
            rebuildIndexes()
            loadAdvancedReport(_advancedReport.value.period)
        }

        result
    }

    suspend fun addPayment(customerId: String, amount: Long): Boolean = writeMutex.withLock {
        val customer = customer(customerId) ?: return@withLock false
        if (amount <= 0 || amount > balance(customer)) return@withLock false
        val entry = repository.addPayment(customerId, amount)
        rememberLocalActor(entry.id)
        _entries.value = listOf(entry) + _entries.value.filterNot { it.id == entry.id }
        rebuildIndexes()
        loadAdvancedReport(_advancedReport.value.period)
        true
    }

    suspend fun updateEntry(
        entryId: String,
        amount: Long,
        bottles: Int?,
        bottlePrice: Long?,
        details: String
    ): MutationResult = writeMutex.withLock {
        val result = repository.updateEntry(entryId, amount, bottles, bottlePrice, details)
        if (result.success) {
            _entries.value = _entries.value.map { entry ->
                if (entry.id == entryId) {
                    entry.copy(
                        amount = amount,
                        bottles = if (entry.type == EntryType.DEBT) bottles?.takeIf { it > 0 } else null,
                        bottlePrice = if (entry.type == EntryType.DEBT) bottlePrice?.takeIf { it > 0L } else null,
                        details = if (entry.type == EntryType.DEBT) details.trim() else entry.details
                    )
                } else entry
            }
            rebuildIndexes()
            loadAdvancedReport(_advancedReport.value.period)
        }
        result
    }

    suspend fun deleteEntry(entryId: String): MutationResult = writeMutex.withLock {
        val result = repository.deleteEntry(entryId)
        if (result.success) {
            _entries.value = _entries.value.filterNot { it.id == entryId }
            rebuildIndexes()
            loadAdvancedReport(_advancedReport.value.period)
        }
        result
    }

    fun totalDebt(): Long = balanceByCustomer.values.sum()

    fun indebtedCustomersCount(): Int = balanceByCustomer.values.count { it > 0L }

    fun todayEntries(type: EntryType? = null): List<LedgerEntry> =
        when (type) {
            EntryType.DEBT -> todayDebtEntriesCache
            EntryType.PAYMENT -> todayPaymentEntriesCache
            null -> todayEntriesCache
        }

    fun todayCollections(): Long = todayCollectionsCache

    fun todayDebts(): Long = todayDebtsCache

    fun topDebtors(): List<Customer> = topDebtorsCache

    fun lastEntryFor(customerId: String): LedgerEntry? = lastEntryByCustomer[customerId]

    fun lastPaymentFor(customerId: String): LedgerEntry? = lastPaymentByCustomer[customerId]

    fun exportJson(): String = repository.exportJson()

    fun createBackupJson(): String = repository.createBackupJson()

    fun previewBackup(raw: String): BackupPreview =
        repository.previewBackup(raw)

    suspend fun restoreBackup(raw: String): BackupRestoreResult {
        val result = writeMutex.withLock { repository.restoreBackup(raw) }
        if (result.success) {
            loadAdvancedReport(_advancedReport.value.period)
        }
        return result
    }

    fun markManualBackupCreated(timestamp: Long = System.currentTimeMillis()) {
        repository.markManualBackupCreated(timestamp)
    }

    fun lastBackupAt(): Long = repository.lastBackupAt()

    fun autoBackupInterval(): AutoBackupInterval =
        repository.autoBackupInterval()

    suspend fun setAutoBackupInterval(interval: AutoBackupInterval) {
        repository.setAutoBackupInterval(interval)
    }

    suspend fun unlockWithPin(pin: String): SecurityMutationResult {
        val success = withContext(Dispatchers.Default) { security.verifyPin(pin) }
        if (success) {
            _isUnlocked.value = true
            return SecurityMutationResult(true, "تم فتح التطبيق.")
        }
        return SecurityMutationResult(false, security.pinFailureMessage())
    }

    fun unlockWithBiometric() {
        if (_securityState.value.pinEnabled && _securityState.value.biometricEnabled) {
            _isUnlocked.value = true
        }
    }

    fun lockNow() {
        if (_securityState.value.pinEnabled) _isUnlocked.value = false
    }

    fun onAppBackgrounded() {
        security.markBackgrounded()
    }

    fun onAppForegrounded() {
        refreshSecurityState()
        if (security.shouldLockOnForeground()) {
            _isUnlocked.value = false
        }
    }

    suspend fun setPin(pin: String): SecurityMutationResult {
        val result = withContext(Dispatchers.Default) { security.setPin(pin) }
        if (result.success) {
            _isUnlocked.value = true
            refreshSecurityState()
        }
        return result
    }

    suspend fun changePin(currentPin: String, newPin: String): SecurityMutationResult {
        val result = withContext(Dispatchers.Default) { security.changePin(currentPin, newPin) }
        if (result.success) refreshSecurityState()
        return result
    }

    suspend fun disablePin(currentPin: String): SecurityMutationResult {
        val result = withContext(Dispatchers.Default) { security.disablePin(currentPin) }
        if (result.success) {
            _isUnlocked.value = true
            refreshSecurityState()
        }
        return result
    }

    fun setBiometricEnabled(enabled: Boolean) {
        security.setBiometricEnabled(enabled)
        refreshSecurityState()
    }

    fun setHideAmounts(enabled: Boolean) {
        security.setHideAmounts(enabled)
        refreshSecurityState()
        // Refresh server-side FCM privacy choice for this device whenever it changes.
        com.radwan.raadpharmacy.cloud.CloudPushRegistrationWorker.enqueue(app)
    }

    fun setSecureScreen(enabled: Boolean) {
        security.setSecureScreen(enabled)
        refreshSecurityState()
    }

    fun setLockTimeoutSeconds(seconds: Int) {
        security.setLockTimeoutSeconds(seconds)
        refreshSecurityState()
    }

    private fun refreshSecurityState() {
        _securityState.value = security.state()
    }

    fun setReminderEnabled(enabled: Boolean) {
        reminders.setEnabled(enabled)
        refreshReminderSettings()
        ReminderScheduler.apply(app, _reminderSettings.value)
    }

    fun setReminderFrequency(frequency: ReminderFrequency) {
        reminders.setFrequency(frequency)
        refreshReminderSettings()
        ReminderScheduler.apply(app, _reminderSettings.value)
    }

    fun setReminderMinimumAge(days: Int) {
        reminders.setMinimumAgeDays(days)
        refreshReminderSettings()
        ReminderScheduler.apply(app, _reminderSettings.value)
    }

    fun runReminderCheckNow() {
        ReminderScheduler.runNow(app)
    }

    fun financialFeedbackSettings(): FinancialFeedbackSettings =
        financialFeedback.state()

    fun setOperationSound(preset: OperationSoundPreset) {
        financialFeedback.setOperationSound(preset)
    }

    fun setNotificationSound(preset: NotificationSoundPreset) {
        financialFeedback.setNotificationSound(preset)
    }

    fun setOperationSoundEnabled(enabled: Boolean) {
        financialFeedback.setOperationSoundEnabled(enabled)
    }

    fun previewOperationSound(preset: OperationSoundPreset) {
        viewModelScope.launch(Dispatchers.Default) {
            FinancialOperationFeedback.playOperationSound(app, preset)
        }
    }

    fun previewNotificationSound(preset: NotificationSoundPreset) {
        viewModelScope.launch(Dispatchers.Default) {
            FinancialOperationFeedback.playNotificationSound(app, preset)
        }
    }

    fun playFinancialSuccessSound() {
        viewModelScope.launch(Dispatchers.Default) {
            FinancialOperationFeedback.playSelectedOperationSound(app)
        }
    }

    fun scheduleFinancialOperationNotification(
        receipt: FinancialOperationReceipt,
        delayMillis: Long = 2_000L
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            delay(delayMillis.coerceAtLeast(0L))
            FinancialOperationFeedback.postNotification(app, receipt)
        }
    }

    fun refreshReminderSettings() {
        _reminderSettings.value = reminders.state()
    }

    fun loadAdvancedReport(period: ReportPeriodV11) {
        _advancedReport.value = _advancedReport.value.copy(
            period = period,
            loading = true,
            errorMessage = null
        )
        viewModelScope.launch {
            val result = runCatching { reportRepository.load(period) }
            _advancedReport.value = result.getOrElse {
                _advancedReport.value.copy(
                    period = period,
                    loading = false,
                    errorMessage = "تعذر تحميل التقرير. حاول مرة أخرى."
                )
            }
        }
    }

    suspend fun resetDemoData() {
        writeMutex.withLock {
            repository.resetDemoData()
        }
        loadAdvancedReport(_advancedReport.value.period)
    }

    private suspend fun rebuildIndexes() {
        val customers = _customers.value
        val entries = _entries.value
        val indexes = withContext(Dispatchers.Default) { buildIndexes(customers, entries) }
        applyIndexes(indexes)
    }

    private fun applyIndexes(indexes: LedgerIndexes) {
        entriesByCustomer = indexes.groups
        lastEntryByCustomer = indexes.lastEntries
        lastPaymentByCustomer = indexes.lastPayments
        balanceByCustomer = indexes.balances
        todayEntriesCache = indexes.todayEntries
        todayDebtEntriesCache = indexes.todayDebts
        todayPaymentEntriesCache = indexes.todayPayments
        todayDebtsCache = indexes.todayDebts.sumOf { it.amount }
        todayCollectionsCache = indexes.todayPayments.sumOf { it.amount }
        topDebtorsCache = indexes.topDebtors
    }

    private data class LedgerIndexes(
        val groups: Map<String, List<LedgerEntry>>,
        val lastEntries: Map<String, LedgerEntry>,
        val lastPayments: Map<String, LedgerEntry>,
        val balances: Map<String, Long>,
        val todayEntries: List<LedgerEntry>,
        val todayDebts: List<LedgerEntry>,
        val todayPayments: List<LedgerEntry>,
        val topDebtors: List<Customer>
    )

    private fun buildIndexes(customers: List<Customer>, entries: List<LedgerEntry>): LedgerIndexes {
        val sortedGroups = entries
            .groupBy { it.customerId }
            .mapValues { (_, list) -> list.sortedByDescending { it.createdAt } }

        val lastEntries = sortedGroups.mapNotNull { (id, list) ->
            list.firstOrNull()?.let { id to it }
        }.toMap()
        val lastPayments = sortedGroups.mapNotNull { (id, list) ->
            list.firstOrNull { it.type == EntryType.PAYMENT }?.let { id to it }
        }.toMap()

        val balances = customers.associate { customer ->
            val movement = sortedGroups[customer.id].orEmpty().sumOf { entry ->
                if (entry.type == EntryType.DEBT) entry.amount else -entry.amount
            }
            customer.id to (customer.openingDebt + movement).coerceAtLeast(0L)
        }

        val today = LocalDate.now()
        val todayEntries = entries.asSequence()
            .filter { entry ->
                Instant.ofEpochMilli(entry.createdAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate() == today
            }
            .sortedByDescending { it.createdAt }
            .toList()
        val todayDebts = todayEntries.filter { it.type == EntryType.DEBT }
        val todayPayments = todayEntries.filter { it.type == EntryType.PAYMENT }

        val topDebtors = customers.asSequence()
            .filter { balances[it.id].orZero() > 0L }
            .sortedByDescending { balances[it.id].orZero() }
            .toList()
        return LedgerIndexes(sortedGroups, lastEntries, lastPayments, balances,
            todayEntries, todayDebts, todayPayments, topDebtors)
    }

    private fun Long?.orZero(): Long = this ?: 0L
}

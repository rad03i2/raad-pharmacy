package com.radwan.raadpharmacy.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.radwan.raadpharmacy.MainActivity
import com.radwan.raadpharmacy.R
import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.data.PharmacyLedgerDatabase
import com.radwan.raadpharmacy.data.LedgerEntry
import com.radwan.raadpharmacy.data.toModel
import com.radwan.raadpharmacy.security.AppSecurityStore
import com.radwan.raadpharmacy.util.formatMoney
import java.util.concurrent.TimeUnit

enum class ReminderFrequency(val storageValue: String, val hours: Long) {
    DAILY("daily", 24L),
    EVERY_THREE_DAYS("three_days", 72L),
    WEEKLY("weekly", 168L);

    companion object {
        fun fromStorage(value: String?): ReminderFrequency =
            entries.firstOrNull { it.storageValue == value } ?: WEEKLY
    }
}

data class ReminderSettings(
    val enabled: Boolean,
    val frequency: ReminderFrequency,
    val minimumAgeDays: Int,
    val lastCheckAt: Long
)

class ReminderStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        if (!prefs.getBoolean("weekly_defaults_v334", false)) {
            prefs.edit().putBoolean(KEY_ENABLED, true)
                .putString(KEY_FREQUENCY, ReminderFrequency.WEEKLY.storageValue)
                .putInt(KEY_MIN_AGE_DAYS, 7)
                .putBoolean("weekly_defaults_v334", true).apply()
        }
    }

    fun state(): ReminderSettings = ReminderSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        frequency = ReminderFrequency.fromStorage(prefs.getString(KEY_FREQUENCY, null)),
        minimumAgeDays = prefs.getInt(KEY_MIN_AGE_DAYS, 7).let {
            if (it in setOf(7, 15, 30)) it else 7
        },
        lastCheckAt = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
    )

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setFrequency(frequency: ReminderFrequency) {
        prefs.edit().putString(KEY_FREQUENCY, frequency.storageValue).apply()
    }

    fun setMinimumAgeDays(days: Int) {
        prefs.edit().putInt(KEY_MIN_AGE_DAYS, days.coerceIn(7, 30)).apply()
    }

    fun markChecked(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_CHECK_AT, now).apply()
    }

    fun canNotify(customerId: String, frequency: ReminderFrequency, now: Long): Boolean {
        val last = prefs.getLong(KEY_NOTIFIED_PREFIX + customerId, 0L)
        val cooldownHours = when (frequency) {
            ReminderFrequency.DAILY -> 20L
            ReminderFrequency.EVERY_THREE_DAYS -> 60L
            ReminderFrequency.WEEKLY -> 144L
        }
        return last <= 0L || now - last >= TimeUnit.HOURS.toMillis(cooldownHours)
    }

    fun markNotified(customerId: String, now: Long) {
        prefs.edit().putLong(KEY_NOTIFIED_PREFIX + customerId, now).apply()
    }

    companion object {
        private const val PREFS_NAME = "gas_ledger_reminders"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FREQUENCY = "frequency"
        private const val KEY_MIN_AGE_DAYS = "minimum_age_days"
        private const val KEY_LAST_CHECK_AT = "last_check_at"
        private const val KEY_NOTIFIED_PREFIX = "last_notified_"
    }
}

object ReminderScheduler {
    private const val UNIQUE_WORK = "gas-ledger-smart-reminders"

    fun apply(context: Context, settings: ReminderSettings) {
        ensureChannel(context)
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!settings.enabled) {
            manager.cancelUniqueWork(UNIQUE_WORK)
            return
        }

        val request = PeriodicWorkRequestBuilder<DebtReminderWorker>(
            settings.frequency.hours,
            TimeUnit.HOURS
        )
            .setInitialDelay(30, TimeUnit.MINUTES)
            .build()

        manager.enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun runNow(context: Context) {
        ensureChannel(context)
        WorkManager.getInstance(context.applicationContext)
            .enqueue(OneTimeWorkRequestBuilder<DebtReminderWorker>().build())
    }

    fun ensureChannel(context: Context): String =
        LedgerNotificationChannels.ensure(context, LedgerNotificationType.REMINDER)

}

internal data class ReminderCandidate(
    val customer: Customer,
    val balance: Long,
    val ageDays: Long,
    val tierDays: Int,
    val topDebtor: Boolean
)

internal object ReminderEngine {
    private data class DebtPortion(
        val createdAt: Long,
        var remaining: Long
    )

    fun candidates(
        customers: List<Customer>,
        entries: List<LedgerEntry>,
        minimumAgeDays: Int,
        now: Long = System.currentTimeMillis()
    ): List<ReminderCandidate> {
        val byCustomer = entries.groupBy { it.customerId }
        val balances = customers.associate { customer ->
            customer.id to openDebtState(customer, byCustomer[customer.id].orEmpty(), now)
        }

        val topIds = balances.entries
            .filter { it.value.first > 0L }
            .sortedByDescending { it.value.first }
            .take(3)
            .mapTo(hashSetOf()) { it.key }

        return customers.mapNotNull { customer ->
            val state = balances[customer.id] ?: return@mapNotNull null
            val balance = state.first
            val ageDays = state.second
            if (balance <= 0L || ageDays < minimumAgeDays) return@mapNotNull null

            val tier = when {
                ageDays >= 30L -> 30
                ageDays >= 15L -> 15
                ageDays >= 7L -> 7
                else -> return@mapNotNull null
            }

            ReminderCandidate(
                customer = customer,
                balance = balance,
                ageDays = ageDays,
                tierDays = tier,
                topDebtor = customer.id in topIds
            )
        }.sortedWith(
            compareByDescending<ReminderCandidate> { it.tierDays }
                .thenByDescending { it.topDebtor }
                .thenByDescending { it.balance }
        )
    }

    private fun openDebtState(
        customer: Customer,
        entries: List<LedgerEntry>,
        now: Long
    ): Pair<Long, Long> {
        val portions = mutableListOf<DebtPortion>()
        if (customer.openingDebt > 0L) {
            portions += DebtPortion(customer.createdAt, customer.openingDebt)
        }

        entries.sortedWith(compareBy<LedgerEntry> { it.createdAt }.thenBy { it.id })
            .forEach { entry ->
                when (entry.type) {
                    EntryType.DEBT -> portions += DebtPortion(entry.createdAt, entry.amount)
                    EntryType.PAYMENT -> {
                        var paymentLeft = entry.amount
                        var index = 0
                        while (paymentLeft > 0L && index < portions.size) {
                            val portion = portions[index]
                            if (portion.remaining <= 0L) {
                                index++
                                continue
                            }
                            val used = minOf(paymentLeft, portion.remaining)
                            portion.remaining -= used
                            paymentLeft -= used
                            if (portion.remaining == 0L) index++
                        }
                    }
                }
            }

        val open = portions.filter { it.remaining > 0L }
        val balance = open.sumOf { it.remaining }
        val oldest = open.minOfOrNull { it.createdAt } ?: now
        val days = TimeUnit.MILLISECONDS.toDays((now - oldest).coerceAtLeast(0L))
        return balance to days
    }
}

class DebtReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = ReminderStore(applicationContext)
        val settings = store.state()
        if (!settings.enabled) return Result.success()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            store.markChecked()
            return Result.success()
        }

        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            store.markChecked()
            return Result.success()
        }

        ReminderScheduler.ensureChannel(applicationContext)

        return runCatching {
            val dao = PharmacyLedgerDatabase.get(applicationContext).dao()
            val customers = dao.getCustomers().map { it.toModel() }
            val entries = dao.getEntries().map { it.toModel() }
            val candidates = ReminderEngine.candidates(
                customers = customers,
                entries = entries,
                minimumAgeDays = settings.minimumAgeDays
            )

            val now = System.currentTimeMillis()
            val selected = candidates
                .filter { store.canNotify(it.customer.id, settings.frequency, now) }
                .take(MAX_INDIVIDUAL_NOTIFICATIONS)

            selected.forEachIndexed { index, candidate ->
                notifyCustomer(candidate, alert = index == 0)
                store.markNotified(candidate.customer.id, now)
            }

            if (candidates.size > MAX_INDIVIDUAL_NOTIFICATIONS && selected.isNotEmpty()) {
                notifySummary(candidates.size, candidates.first().tierDays)
            }

            store.markChecked(now)
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    private fun notifyCustomer(candidate: ReminderCandidate, alert: Boolean) {
        val privacy = AppSecurityStore(applicationContext).state()
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_CUSTOMER_ID, candidate.customer.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            candidate.customer.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val tierText = when (candidate.tierDays) {
            30 -> "30 يومًا أو أكثر"
            15 -> "15 يومًا أو أكثر"
            else -> "7 أيام أو أكثر"
        }
        val amountText = if (privacy.hideAmounts) {
            ""
        } else {
            " • " + formatMoney(candidate.balance)
        }
        val topText = if (candidate.topDebtor) " • من أعلى المديونيات" else ""
        val body = "دين مفتوح منذ " + tierText + amountText + topText

        val publicNotification = NotificationCompat.Builder(applicationContext, ReminderScheduler.ensureChannel(applicationContext))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("دفتر صيدلية رعد")
            .setContentText("لديك حساب يحتاج متابعة.")
            .build()

        val notification = NotificationCompat.Builder(applicationContext, ReminderScheduler.ensureChannel(applicationContext))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(candidate.customer.name)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setGroup(GROUP_KEY)
            .setSilent(!alert)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(
                customerNotificationId(candidate.customer.id),
                notification
            )
        } catch (_: SecurityException) {
            // Permission can be revoked between the worker-level check and posting.
        }
    }

    private fun notifySummary(count: Int, highestTier: Int) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            SUMMARY_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = count.toString() + " حسابات تحتاج متابعة" +
            if (highestTier >= 30) "، بينها ديون أقدم من 30 يومًا." else "."

        val notification = NotificationCompat.Builder(applicationContext, ReminderScheduler.ensureChannel(applicationContext))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ملخص متابعة الديون")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setSilent(true)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(SUMMARY_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the worker-level check and posting.
        }
    }

    private fun customerNotificationId(customerId: String): Int =
        10_000 + (customerId.hashCode() and 0x3FFF)

    companion object {
        const val CHANNEL_ID = "debt_follow_up"
        private const val GROUP_KEY = "gas_ledger_debt_follow_up"
        private const val MAX_INDIVIDUAL_NOTIFICATIONS = 2
        private const val SUMMARY_NOTIFICATION_ID = 9_001
    }
}

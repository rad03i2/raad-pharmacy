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
import androidx.work.*
import com.radwan.raadpharmacy.MainActivity
import com.radwan.raadpharmacy.R
import com.radwan.raadpharmacy.data.*
import com.radwan.raadpharmacy.security.AppSecurityStore
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class ShortReminderStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("raad_short_reminders_v23", Context.MODE_PRIVATE)
    fun enabled() = prefs.getBoolean("enabled", false)
    fun setEnabled(value: Boolean) { check(prefs.edit().putBoolean("enabled", value).commit()) }
    fun notified(key: String) = prefs.getBoolean("sent:$key", false)
    fun mark(key: String) { check(prefs.edit().putBoolean("sent:$key", true).commit()) }
}

/** Separate unique work/tag: enabling or disabling this feature never cancels existing reminders. */
object ShortReminderScheduler {
    internal const val TAG = "raad-short-repayment-v23"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: Job? = null
    @Synchronized fun reconcile(context: Context) {
        val app = context.applicationContext
        observer?.cancel()
        observer = scope.launch {
            try {
                val manager = WorkManager.getInstance(app)
                val store = ShortReminderStore(app)
                if (!store.enabled()) { manager.cancelAllWorkByTag(TAG); return@launch }
                PharmacyLedgerDatabase.get(app).dao().observeEntries().collect { rows ->
                if (!store.enabled()) return@collect
                val now = System.currentTimeMillis()
                rows.map { it.toModel() }.forEach { entry ->
                    val due = RepaymentTerms.shortDueAt(entry) ?: return@forEach
                    val key = "${entry.id}:$due"
                    if (!store.notified(key)) manager.enqueueUniqueWork("$TAG:$key", ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<ShortReminderWorker>()
                            .setInputData(workDataOf("entry" to entry.id, "due" to due, "key" to key))
                            .setInitialDelay((due - now).coerceAtLeast(0), TimeUnit.MILLISECONDS).addTag(TAG).build())
                }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { android.util.Log.w("ShortReminders", "Scheduling deferred", error) }
        }
    }
}

class ShortReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = ShortReminderStore(applicationContext)
        if (!applicationContext.getSharedPreferences("raad_cloud_auth", Context.MODE_PRIVATE)
                .getBoolean("has_offline_session", false)) return Result.success()
        val key = inputData.getString("key") ?: return Result.success()
        if (!store.enabled() || store.notified(key)) return Result.success()
        return try {
            val dao = PharmacyLedgerDatabase.get(applicationContext).dao()
            val entry = inputData.getString("entry")?.let { dao.getEntryById(it)?.toModel() } ?: return Result.success()
            val due = RepaymentTerms.shortDueAt(entry) ?: return Result.success()
            if (due != inputData.getLong("due", 0L)) return Result.success()
            if (due > System.currentTimeMillis()) return Result.retry()
            val customer = dao.getCustomerById(entry.customerId)?.toModel() ?: return Result.success()
            val entries = dao.getEntriesForCustomer(customer.id).map { it.toModel() }
            if (entry.id !in DebtFollowupEngine.calculate(customer, entries).unpaidEntryIds) return Result.success()
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.retry()
            val manager = NotificationManagerCompat.from(applicationContext)
            if (!manager.areNotificationsEnabled()) return Result.retry()
            val intent = Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_CUSTOMER_ID, customer.id)
            val pending = PendingIntent.getActivity(applicationContext, key.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val hidden = AppSecurityStore(applicationContext).state().hideAmounts
            val body = if (hidden) "حان موعد التذكير القصير بالتسديد." else "حان موعد التسديد المتفق عليه بعد ${RepaymentTerms.label(entry.repaymentDays!!)}."
            // Stable tag/id makes a retry after process death replace the same notification.
            manager.notify(ShortReminderScheduler.TAG, key.hashCode(), NotificationCompat.Builder(applicationContext,
                ReminderScheduler.ensureChannel(applicationContext)).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("تذكير قصير • ${customer.name}").setContentText(body).setContentIntent(pending)
                .setOnlyAlertOnce(true).setAutoCancel(true).build())
            store.mark(key)
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}

package com.radwan.raadpharmacy.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.radwan.raadpharmacy.MainActivity
import com.radwan.raadpharmacy.R
import com.radwan.raadpharmacy.data.DebtFollowupEngine
import com.radwan.raadpharmacy.data.PharmacyLedgerDatabase
import com.radwan.raadpharmacy.data.toModel
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Aggregated, non-personal debt ageing milestones (30, 60 and 90 days).
 * This does not send WhatsApp messages, add debt, or modify any customer record.
 */
object FollowupMilestones {
    data class Milestone(val key: String, val thresholdDays: Int)
    fun eligible(accounts: List<DebtFollowupEngine.Account>): List<Milestone> =
        accounts.mapNotNull { account ->
            val age = account.ageDays ?: return@mapNotNull null
            val oldest = account.oldestUnpaidAt ?: return@mapNotNull null
            val threshold = when {
                age >= 90L -> 90
                age >= 60L -> 60
                age >= 30L -> 30
                else -> return@mapNotNull null
            }
            // A new oldest outstanding tranche begins a separate debt cycle.
            Milestone("${account.customer.id}:$oldest", threshold)
        }

    data class Summary(val entered30: Int, val reached60: Int, val reached90: Int) {
        val total get() = entered30 + reached60 + reached90
        fun message(): String {
            val details = buildList {
                if (entered30 > 0) add("$entered30 حسابًا دخلت 30 يومًا دون تسديد كامل")
                if (reached60 > 0) add("$reached60 حسابًا بلغت 60 يومًا")
                if (reached90 > 0) add("$reached90 حسابًا بلغت 90 يومًا أو أكثر")
            }
            return details.joinToString("، ") + ". اضغط لفتح المتابعة وكشف الحسابات."
        }
    }

    fun pending(milestones: List<Milestone>, notified: (String) -> Int): Pair<List<Milestone>, Summary> {
        val changed = milestones.filter { it.thresholdDays > notified(it.key) }
        val summary = Summary(
            entered30 = changed.count { it.thresholdDays == 30 },
            reached60 = changed.count { it.thresholdDays == 60 },
            reached90 = changed.count { it.thresholdDays == 90 }
        )
        return changed to summary
    }
}

object FollowupNotificationScheduler {
    private const val PERIODIC = "raad-followup-milestones-periodic-v1"
    private const val ONCE = "raad-followup-milestones-now-v1"

    fun ensure(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FollowupIntelligenceWorker>(24, TimeUnit.HOURS).build()
        )
        runNow(context)
    }

    fun runNow(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ONCE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<FollowupIntelligenceWorker>().build()
        )
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork(PERIODIC)
        manager.cancelUniqueWork(ONCE)
    }
}

class FollowupIntelligenceWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        // Never alert on old local records after an explicit sign-out.
        val authenticated = context.getSharedPreferences("raad_cloud_auth", Context.MODE_PRIVATE)
            .getBoolean("has_offline_session", false)
        if (!authenticated) return Result.success()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return Result.success()
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return Result.success()

        return try {
            val dao = PharmacyLedgerDatabase.get(context).dao()
            val customerRows = dao.getCustomers().map { it.toModel() }
            val entryRows = dao.getEntries().map { it.toModel() }
            val accounts = DebtFollowupEngine.build(
                customerRows, entryRows, LocalDate.now(DebtFollowupEngine.IRAQ_ZONE)
            )
            val prefs = context.getSharedPreferences("raad_followup_milestones_v1", Context.MODE_PRIVATE)
            val (newItems, summary) = FollowupMilestones.pending(
                FollowupMilestones.eligible(accounts),
                notified = { prefs.getInt(it, 0) }
            )
            if (summary.total == 0) return Result.success()
            val manager = context.getSystemService(NotificationManager::class.java)
            val channelId = "raad_followup_information_v1"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(NotificationChannel(
                    channelId, "تنبيهات متابعة الديون", NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "تنبيهات معلوماتية عامة عن أعمار الديون دون أسماء أو مبالغ" })
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_FOLLOWUP, true)
            }
            val click = PendingIntent.getActivity(context, 31617, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val body = summary.message()
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("متابعة الديون — صيدلية رعد")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(click)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            NotificationManagerCompat.from(context).notify(31617, notification)
            // Commit only after NotificationManager accepts the notification.
            prefs.edit().apply {
                newItems.forEach { putInt(it.key, it.thresholdDays) }
            }.commit()
            Result.success()
        } catch (_: SecurityException) {
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

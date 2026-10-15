package com.radwan.raadpharmacy.whatsapp

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.radwan.raadpharmacy.cloud.CloudSyncEngine
import com.radwan.raadpharmacy.cloud.CloudSyncScheduler
import com.radwan.raadpharmacy.cloud.SupabaseProvider
import com.radwan.raadpharmacy.data.DebtFollowupEngine
import com.radwan.raadpharmacy.data.FollowupReminderStore
import com.radwan.raadpharmacy.data.PharmacyLedgerDatabase
import com.radwan.raadpharmacy.data.ReadyMessages
import com.radwan.raadpharmacy.data.toModel
import com.radwan.raadpharmacy.util.validatedIraqiWhatsappPhone
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in is device-, account-, number- and customer-specific. No schema changes. */
internal class AutomaticWhatsappStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("raad_auto_whatsapp_v1", Context.MODE_PRIVATE)
    companion object { private val RESERVATION_LOCK = Any() }

    data class Log(val id: String, val phone: String, val state: String, val at: Long)

    fun enabled(): Boolean = prefs.getBoolean("enabled", false)
    fun sender(): String? = prefs.getString("sender", null)
    fun userId(): String? = prefs.getString("account", null)
    fun setEnabled(value: Boolean, sender: String? = null, user: String? = null) {
        val edit = prefs.edit().putBoolean("enabled", value)
        if (value) {
            require(!sender.isNullOrBlank() && !user.isNullOrBlank())
            edit.putString("sender", sender).putString("account", user)
        }
        check(edit.commit()) { "تعذر حفظ تشغيل التذكيرات." }
    }

    /** Must be checked only after the pharmacy has documented recipient permission. */
    fun approved(customerId: String, normalizedPhone: String): Boolean =
        prefs.getString("consent:$customerId", null) == normalizedPhone

    fun approve(customerId: String, normalizedPhone: String, value: Boolean) {
        val edit = prefs.edit()
        if (value) edit.putString("consent:$customerId", normalizedPhone)
        else edit.remove("consent:$customerId")
        check(edit.commit()) { "تعذر حفظ موافقة الزبون." }
    }

    fun reserve(caseKey: String, phone: String): String? = synchronized(RESERVATION_LOCK) {
        require(Regex("^[a-f0-9]{64}$").matches(caseKey))
        if (!enabled()) return@synchronized null
        val key = "case:$caseKey:$phone"
        if (prefs.contains(key)) return@synchronized null
        // Periodic and manual workers can run concurrently; enforce the cap in the same lock.
        if (dayAttempts(LocalDate.now(DebtFollowupEngine.IRAQ_ZONE)) >= 10) return@synchronized null
        // Commit the id before the network request. Failed/ambiguous sends cannot be repeated.
        val id = UUID.randomUUID().toString()
        val row = JSONObject().put("id", id).put("phone", phone)
            .put("state", "uncertain").put("at", System.currentTimeMillis())
        check(prefs.edit().putString(key, id).putString("job:$id", row.toString()).commit()) {
            "تعذر حفظ محاولة التذكير."
        }
        id
    }

    fun record(id: String, state: String) {
        val key = "job:$id"
        val row = JSONObject(prefs.getString(key, null) ?: return)
        row.put("state", state)
        check(prefs.edit().putString(key, row.toString()).commit())
    }

    fun stateFor(caseKey: String, phone: String): String? = runCatching {
        val id = prefs.getString("case:$caseKey:$phone", null) ?: return@runCatching null
        JSONObject(prefs.getString("job:$id", null) ?: return@runCatching null).getString("state")
    }.getOrNull()

    fun logs(): List<Log> = prefs.all.asSequence().filter { it.key.startsWith("job:") }
        .mapNotNull {
            runCatching {
                val j = JSONObject(it.value as String)
                Log(j.getString("id"), j.getString("phone"), j.getString("state"), j.getLong("at"))
            }.getOrNull()
        }.sortedByDescending { it.at }.take(25).toList()

    fun dayAttempts(day: LocalDate): Int = logs().count {
        java.time.Instant.ofEpochMilli(it.at).atZone(DebtFollowupEngine.IRAQ_ZONE).toLocalDate() == day
    }

    fun markChecked(summary: String) {
        prefs.edit().putLong("last_checked", System.currentTimeMillis())
            .putString("last_summary", summary.take(240)).apply()
    }
    fun lastChecked(): Long = prefs.getLong("last_checked", 0L)
    fun lastSummary(): String = prefs.getString("last_summary", null).orEmpty()
}

internal object AutomaticWhatsappPlanner {
    fun due(accounts: List<DebtFollowupEngine.Account>): List<DebtFollowupEngine.Account> =
        accounts.filter { it.balance > 0L && it.overdueAmount > 0L &&
            !it.hasUnknownDates && (it.specialOverdueAmount > 0L || (it.ageDays ?: 0L) >= 30L) }
}

/** WorkManager is best-effort: Doze/battery policies can delay a daily run. */
internal object AutomaticWhatsappScheduler {
    private const val NAME = "raad-auto-whatsapp-daily"
    private const val MANUAL = "raad-auto-whatsapp-check-now"
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun apply(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!AutomaticWhatsappStore(context).enabled()) {
            manager.cancelUniqueWork(NAME)
            manager.cancelUniqueWork(MANUAL)
            return
        }
        val task = PeriodicWorkRequestBuilder<AutomaticWhatsappWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(10, TimeUnit.MINUTES).setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, task)
    }

    fun ensureCatchUp(context: Context) {
        val store = AutomaticWhatsappStore(context)
        if (!store.enabled()) return
        val last = store.lastChecked()
        if (last <= 0L || System.currentTimeMillis() - last > TimeUnit.HOURS.toMillis(22)) checkNow(context)
    }

    fun checkNow(context: Context) {
        val task = OneTimeWorkRequestBuilder<AutomaticWhatsappWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP, task)
    }
}

internal class AutomaticWhatsappWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val store = AutomaticWhatsappStore(app)
        if (!store.enabled()) return Result.success()
        val today = LocalDate.now(DebtFollowupEngine.IRAQ_ZONE)
        var retryable = false
        try {
            if (!CloudSyncScheduler.isEnabled(app)) error("يلزم تسجيل الدخول وتفعيل مزامنة الصيدلية.")
            val auth = SupabaseProvider.client.auth
            auth.awaitInitialization()
            val user = auth.currentSessionOrNull()?.user?.id
            if (user == null || user != store.userId()) error("تغير حساب المستخدم؛ أوقف الإرسال حتى إعادة الاعتماد.")
            retryable = true

            // Never send against a stale local ledger or pending offline collections.
            CloudSyncEngine(app).syncOnce()
            val dao = PharmacyLedgerDatabase.get(app).dao()
            if (dao.restoreHold() == "1" || dao.cloudOutbox().isNotEmpty())
                error("توجد عمليات معلقة أو استعادة بيانات؛ لن تُرسل أي مطالبة غير محدثة.")

            val bridgeStore = PhoneWhatsappStore(app)
            val client = PhoneWhatsappClient(bridgeStore::token)
            val status = client.status()
            if (status.state != "connected" || status.phone != store.sender() ||
                bridgeStore.verifiedAt(status.phone) <= 0L)
                error("المساعد غير متصل أو لم تُعتمد تجربة الإرسال من هذا الرقم.")

            // Reconcile old attempts. Uncertain outcomes are never retried automatically.
            store.logs().forEach { old ->
                if (old.state in listOf("submitting", "submitted", "sent", "uncertain")) {
                    runCatching { client.trial(old.id) }.onSuccess { store.record(old.id, it.state) }
                }
            }

            val entries = dao.getEntries().map { it.toModel() }
            val accounts = AutomaticWhatsappPlanner.due(
                DebtFollowupEngine.build(dao.getCustomers().map { it.toModel() }, entries, today))
            var attempted = 0
            var skipped = 0
            for (account in accounts) {
                if (!store.enabled() || isStopped) break
                if (store.dayAttempts(today) >= 10) break
                val phone = account.customer.phone?.let(::validatedIraqiWhatsappPhone)
                if (phone == null || !store.approved(account.customer.id, phone)) { skipped++; continue }
                // Synchronize again immediately before each financial claim.
                // A payment from a second device may have arrived since the first snapshot.
                CloudSyncEngine(app).syncOnce()
                if (dao.restoreHold() == "1" || dao.cloudOutbox().isNotEmpty())
                    error("توقفت المطالبات لأن المزامنة لم تكتمل.")
                val freshCustomer = dao.getCustomerById(account.customer.id)?.toModel() ?: continue
                val freshEntries = dao.getEntriesForCustomer(account.customer.id).map { it.toModel() }
                val freshAccount = DebtFollowupEngine.calculate(freshCustomer, freshEntries, today)
                if (AutomaticWhatsappPlanner.due(listOf(freshAccount)).isEmpty()) continue
                val currentPhone = freshCustomer.phone?.let(::validatedIraqiWhatsappPhone)
                if (currentPhone == null || currentPhone != phone || !store.approved(freshCustomer.id, currentPhone)) continue
                val case = FollowupReminderStore.caseFor(freshAccount, freshEntries)
                // A confirmed manual WhatsApp reminder for the same debt stage is sufficient.
                if (FollowupReminderStore(app).confirmedAt(case) != null) continue
                val message = ReadyMessages.messageFor(app, freshAccount) +
                    "\nالصورة المرفقة ملخص للحساب وليست كشفًا كاملًا."
                if (message.length > 1800) { skipped++; continue }
                val id = store.reserve(case.fingerprint, phone) ?: continue
                attempted++
                try {
                    if (!store.enabled() || isStopped) continue
                    val image = AutomaticWhatsappReceiptImage.create(app, freshAccount, freshEntries)
                    val job = client.sendReminder(id, phone, case.fingerprint, message, image)
                    store.record(id, job.state)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    store.record(id, "uncertain")
                    throw error
                }
            }
            store.markChecked("فحص مكتمل: $attempted محاولة جديدة، $skipped حساب دون رقم معتمد أو نص صالح.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            store.markChecked("توقف الفحص دون إرسال إضافي: ${error.message ?: "خطأ غير معروف"}")
            // Keep automatic retries bounded. Persisted attempt IDs must not be reused for sending.
            if (retryable && runAttemptCount < 2 && store.enabled()) return Result.retry()
        }
        return Result.success()
    }
}

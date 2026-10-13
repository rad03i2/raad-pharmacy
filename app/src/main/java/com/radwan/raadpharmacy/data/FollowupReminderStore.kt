package com.radwan.raadpharmacy.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Device-local follow-up metadata, kept separate from debts, payments and sync. */
class FollowupReminderStore(context: Context) {
    data class Case(val customerId: String, val fingerprint: String)
    data class Pending(
        val token: String,
        val case: Case,
        val launched: Boolean = false,
        val departed: Boolean = false
    )

    private val prefs = context.applicationContext.getSharedPreferences("raad_followup_reminders_v1", Context.MODE_PRIVATE)

    fun stage(case: Case): Pending = Pending(UUID.randomUUID().toString(), case).also(::writePending)

    fun launched(token: String) {
        pending()?.takeIf { it.token == token }?.let { writePending(it.copy(launched = true)) }
    }

    fun departed() {
        pending()?.let { writePending(it.copy(departed = true)) }
    }

    fun confirmation(): Pending? = pending()?.takeIf { it.launched && it.departed }

    /** A canceled picker or a failed launch must never become a sent reminder. */
    fun discard(token: String) {
        if (pending()?.token == token) check(prefs.edit().remove(PENDING).commit())
    }

    fun confirmSent(token: String, at: Long = System.currentTimeMillis()): Boolean {
        val request = confirmation()?.takeIf { it.token == token } ?: return false
        require(at > 0L)
        val mark = JSONObject().put("case", request.case.fingerprint).put("at", at)
        return prefs.edit().putString(key(request.case.customerId), mark.toString()).remove(PENDING).commit()
    }

    fun confirmedAt(case: Case): Long? = runCatching {
        val mark = JSONObject(prefs.getString(key(case.customerId), null) ?: return@runCatching null)
        mark.getLong("at").takeIf { it > 0L && mark.getString("case") == case.fingerprint }
    }.getOrNull()

    private fun pending(): Pending? = runCatching {
        val row = JSONObject(prefs.getString(PENDING, null) ?: return@runCatching null)
        Pending(row.getString("token"), Case(row.getString("customer"), row.getString("case")),
            row.optBoolean("launched"), row.optBoolean("departed"))
    }.getOrNull()

    private fun writePending(request: Pending) {
        val row = JSONObject().put("token", request.token).put("customer", request.case.customerId)
            .put("case", request.case.fingerprint).put("launched", request.launched).put("departed", request.departed)
        check(prefs.edit().putString(PENDING, row.toString()).commit()) { "تعذر حفظ حالة التذكير على الجهاز." }
    }

    companion object {
        private const val PENDING = "pending"
        private fun key(customerId: String) = "confirmed:$customerId"

        /** Partial payments keep the mark while the same debt is open. New/edited
         * debts, a different unpaid tranche/agreement, or a 60-day escalation reset it. */
        fun caseFor(account: DebtFollowupEngine.Account, entries: List<LedgerEntry>): Case {
            val debts = JSONArray()
            entries.filter { it.customerId == account.customer.id && it.type == EntryType.DEBT && it.amount > 0L }
                .sortedBy { it.id }.forEach {
                    debts.put(JSONArray().put(it.id).put(it.createdAt).put(it.amount).put(it.repaymentDays ?: 0))
                }
            val payload = JSONArray().put(account.customer.id).put(account.customer.createdAt)
                .put(account.customer.openingDebt).put(account.oldestUnpaidAt?.toString().orEmpty())
                .put(account.specialDueAt?.toString().orEmpty()).put(account.specialReminderDays ?: 0)
                .put(ReadyMessages.kindFor(account).name).put(account.balance > 0L).put(debts).toString()
            val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return Case(account.customer.id, hash)
        }
    }
}

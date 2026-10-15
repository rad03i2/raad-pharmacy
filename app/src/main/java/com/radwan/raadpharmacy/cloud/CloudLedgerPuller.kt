package com.radwan.raadpharmacy.cloud

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import java.time.Instant

/** Checkpoints come from committed server rows, never the receiving phone's clock. */
internal class CloudLedgerPuller(
    private val prefs: SharedPreferences,
    private val customers: suspend (String?) -> List<CloudCustomerRow>,
    private val transactions: suspend (String?) -> List<CloudTransactionRow>,
    private val apply: suspend (List<CloudCustomerRow>, List<CloudTransactionRow>, Boolean) -> Unit
) {
    suspend fun pull(forceSnapshot: Boolean = false) {
        // Old last_remote_pull_at values may be days ahead after a phone-clock change.
        // The first upgraded pull repairs missing rows while the DAO preserves its outbox.
        val full = forceSnapshot || !prefs.getBoolean(INITIALIZED, false)
        val customerCursor = if (full) null else cursor(CUSTOMERS)
        val transactionCursor = if (full) null else cursor(TRANSACTIONS)
        val customerRows = customers(customerCursor?.minusMillis(5_000)?.toString())
        val transactionRows = transactions(transactionCursor?.minusMillis(5_000)?.toString())
        val nextCustomer = newest(customerCursor, customerRows.map { it.updatedAt })
        val nextTransaction = newest(transactionCursor, transactionRows.map { it.updatedAt })
        apply(customerRows, transactionRows, full)
        val edit = prefs.edit().putBoolean(INITIALIZED, true).remove("last_remote_pull_at")
        if (full) edit.remove(CUSTOMERS).remove(TRANSACTIONS)
        nextCustomer?.let { edit.putString(CUSTOMERS, it.toString()) }
        nextTransaction?.let { edit.putString(TRANSACTIONS, it.toString()) }
        check(edit.commit()) { "تعذر حفظ تقدم مزامنة الحسابات." }
    }

    private fun cursor(key: String): Instant? = prefs.getString(key, null)?.let(Instant::parse)

    private fun newest(previous: Instant?, timestamps: List<String>): Instant? =
        (timestamps.map(Instant::parse) + listOfNotNull(previous)).maxOrNull()

    companion object {
        private const val INITIALIZED = "server_row_cursors_v1"
        private const val CUSTOMERS = "server_customer_cursor_v1"
        private const val TRANSACTIONS = "server_transaction_cursor_v1"

        fun invalidate(prefs: SharedPreferences) {
            check(prefs.edit().remove(INITIALIZED).remove(CUSTOMERS).remove(TRANSACTIONS)
                .remove("last_remote_pull_at").commit())
        }
    }
}

/** An upload/registration failure must not conceal other devices' committed debts. */
internal suspend fun syncBothDirections(upload: suspend () -> Unit, receive: suspend () -> Unit) {
    val uploadFailure = try { upload(); null }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { failure }
    receive()
    uploadFailure?.let { throw it }
}

package com.radwan.raadpharmacy.data

object LedgerRules {
    /**
     * Editing a movement may not create a new historical overpayment. If an explicitly
     * deleted debt already left an unmatched historical payment, do not lock the
     * entire customer ledger from later harmless edits. We compare the most negative
     * running balance before and after the edit; no compensating debt is ever created.
     */
    private fun minimumRunning(customer: Customer, entries: List<LedgerEntry>): Long? = runCatching {
        var running = customer.openingDebt
        var minimum = minOf(0L, running)
        entries.asSequence()
            .filter { it.customerId == customer.id }
            .sortedWith(compareBy<LedgerEntry> { it.createdAt }.thenBy { it.id })
            .forEach { entry ->
                if (entry.amount <= 0L) return null
                running = if (entry.type == EntryType.DEBT)
                    Math.addExact(running, entry.amount)
                else Math.subtractExact(running, entry.amount)
                minimum = minOf(minimum, running)
            }
        minimum
    }.getOrNull()

    fun isEditChronologicallySafe(
        customer: Customer,
        before: List<LedgerEntry>,
        after: List<LedgerEntry>
    ): Boolean {
        val originalMinimum = minimumRunning(customer, before) ?: return false
        val revisedMinimum = minimumRunning(customer, after) ?: return false
        return revisedMinimum >= minOf(0L, originalMinimum)
    }

    fun isOpeningDebtEditSafe(
        original: Customer,
        updated: Customer,
        entries: List<LedgerEntry>
    ): Boolean {
        val originalMinimum = minimumRunning(original, entries) ?: return false
        val revisedMinimum = minimumRunning(updated, entries) ?: return false
        return revisedMinimum >= minOf(0L, originalMinimum)
    }

    fun isChronologicallyValid(
        customer: Customer,
        entries: List<LedgerEntry>
    ): Boolean {
        var running = customer.openingDebt
        return runCatching {
            entries
                .asSequence()
                .filter { it.customerId == customer.id }
                .sortedWith(compareBy<LedgerEntry> { it.createdAt }.thenBy { it.id })
                .forEach { entry ->
                    running = if (entry.type == EntryType.DEBT) {
                        Math.addExact(running, entry.amount)
                    } else {
                        Math.subtractExact(running, entry.amount)
                    }
                    if (running < 0L) return false
                }
            true
        }.getOrDefault(false)
    }
}

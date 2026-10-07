package com.radwan.raadpharmacy.data

object LedgerRules {
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

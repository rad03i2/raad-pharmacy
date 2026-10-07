package com.radwan.raadpharmacy.util

import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.LedgerEntry
import com.radwan.raadpharmacy.data.EntryType
import java.text.Collator
import java.util.Locale

enum class CustomerOrdering(val label: String) {
    NAME("الاسم (أبجدي)"), MOST_ACTIVE("الأكثر تعاملًا"), HIGHEST("الأكثر دينًا"),
    LATEST("آخر تعامل"), AREA("المنطقة")
}

fun orderCustomers(customers: List<Customer>, entries: List<LedgerEntry>, ordering: CustomerOrdering): List<Customer> {
    val counts = HashMap<String, Int>()
    val movements = HashMap<String, Long>()
    val latest = HashMap<String, Long>()
    entries.forEach { entry ->
        counts[entry.customerId] = (counts[entry.customerId] ?: 0) + 1
        movements[entry.customerId] = (movements[entry.customerId] ?: 0L) +
            if (entry.type == EntryType.DEBT) entry.amount else -entry.amount
        latest[entry.customerId] = maxOf(latest[entry.customerId] ?: Long.MIN_VALUE, entry.createdAt)
    }
    val collator = Collator.getInstance(Locale("ar", "IQ")).apply { strength = Collator.PRIMARY }
    val alphabetic = Comparator<Customer> { a, b -> collator.compare(a.name.trim(), b.name.trim()) }.thenBy { it.id }
    val comparator = when (ordering) {
        CustomerOrdering.NAME -> alphabetic
        CustomerOrdering.MOST_ACTIVE -> compareByDescending<Customer> { counts[it.id] ?: 0 }.then(alphabetic)
        CustomerOrdering.HIGHEST -> compareByDescending<Customer> {
            (it.openingDebt + (movements[it.id] ?: 0L)).coerceAtLeast(0L)
        }.then(alphabetic)
        CustomerOrdering.LATEST -> compareByDescending<Customer> { latest[it.id] ?: it.createdAt }.then(alphabetic)
        CustomerOrdering.AREA -> Comparator<Customer> { a, b -> collator.compare(a.area.trim(), b.area.trim()) }.then(alphabetic)
    }
    return customers.sortedWith(comparator)
}

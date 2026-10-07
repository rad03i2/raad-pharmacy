package com.radwan.raadpharmacy.util

import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.LedgerEntry
import com.radwan.raadpharmacy.data.EntryType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class AreaOverview(val name: String, val customers: List<Customer>, val balances: Map<String, Long>,
    val debt: Long, val collectedToday: Long, val openAccounts: Int)

enum class AreaSort(val label: String) { DEBT("أعلى دين"), CUSTOMERS("أكثر زبائن"), NAME("الاسم") }

private val areaWhitespace = Regex("\\s+")

fun buildAreaOverview(customers: List<Customer>, entries: List<LedgerEntry>, today: LocalDate = LocalDate.now()): List<AreaOverview> {
    val movement = HashMap<String, Long>()
    val paid = HashMap<String, Long>()
    val zone = ZoneId.systemDefault()
    entries.forEach { e ->
        movement[e.customerId] = (movement[e.customerId] ?: 0L) + if (e.type == EntryType.DEBT) e.amount else -e.amount
        if (e.type == EntryType.PAYMENT && Instant.ofEpochMilli(e.createdAt).atZone(zone).toLocalDate() == today)
            paid[e.customerId] = (paid[e.customerId] ?: 0L) + e.amount
    }
    return customers.groupBy { it.area.trim().replace(areaWhitespace, " ").ifBlank { "غير محددة" } }.map { (name, members) ->
        val balances = members.associate { it.id to (it.openingDebt + (movement[it.id] ?: 0L)).coerceAtLeast(0L) }
        AreaOverview(name, members.sortedWith(compareByDescending<Customer> { balances[it.id] ?: 0L }.thenBy { it.name }),
            balances, balances.values.sum(), members.sumOf { paid[it.id] ?: 0L }, balances.values.count { it > 0L })
    }
}

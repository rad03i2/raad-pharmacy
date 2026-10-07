package com.radwan.raadpharmacy.data

enum class EntryType {
    DEBT,
    PAYMENT
}

data class Customer(
    val id: String,
    val name: String,
    val phone: String? = null,
    val area: String = "",
    val address: String = "",
    val openingDebt: Long = 0L,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class LedgerEntry(
    val id: String,
    val customerId: String,
    val type: EntryType,
    val amount: Long,
    val bottles: Int? = null,
    val bottlePrice: Long? = null,
    val details: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

fun customerBalance(
    customer: Customer,
    entries: List<LedgerEntry>
): Long {
    val movementBalance = entries
        .asSequence()
        .filter { it.customerId == customer.id }
        .sumOf { if (it.type == EntryType.DEBT) it.amount else -it.amount }
    return (customer.openingDebt + movementBalance).coerceAtLeast(0L)
}


data class MutationResult(
    val success: Boolean,
    val message: String
)

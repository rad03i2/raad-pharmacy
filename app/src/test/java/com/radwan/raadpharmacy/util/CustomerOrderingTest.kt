package com.radwan.raadpharmacy.util

import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.LedgerEntry
import com.radwan.raadpharmacy.data.EntryType
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomerOrderingTest {
    private val customers = listOf(Customer("z", "زيد", openingDebt = 50000, createdAt = 1),
        Customer("b", "بلال", openingDebt = 500, createdAt = 2), Customer("a", "أحمد", openingDebt = 500, createdAt = 3))
    private val entries = listOf(LedgerEntry("1", "a", EntryType.DEBT, 250, createdAt = 10),
        LedgerEntry("2", "a", EntryType.PAYMENT, 250, createdAt = 20),
        LedgerEntry("3", "b", EntryType.DEBT, 1000, createdAt = 30))

    @Test fun defaultAndChipOrderStartWithArabicAlphabet() {
        assertEquals(listOf(CustomerOrdering.NAME, CustomerOrdering.MOST_ACTIVE, CustomerOrdering.HIGHEST,
            CustomerOrdering.LATEST, CustomerOrdering.AREA), CustomerOrdering.entries)
        assertEquals(listOf("a", "b", "z"), orderCustomers(customers, entries, CustomerOrdering.NAME).map { it.id })
    }
    @Test fun mostActiveCountsDebtsAndPaymentsRatherThanMoney() {
        assertEquals(listOf("a", "b", "z"), orderCustomers(customers, entries, CustomerOrdering.MOST_ACTIVE).map { it.id })
    }
    @Test fun highestDebtUsesOpeningDebtAndNetMovements() {
        assertEquals(listOf("z", "b", "a"), orderCustomers(customers, entries, CustomerOrdering.HIGHEST).map { it.id })
    }
    @Test fun lastInteractionDiffersFromMostActive() {
        assertEquals(listOf("b", "a", "z"), orderCustomers(customers, entries, CustomerOrdering.LATEST).map { it.id })
    }
    @Test fun tiesUseAlphabeticNameThenStableId() {
        val names = listOf(Customer("2", "بلال"), Customer("1", "أحمد"), Customer("3", "أحمد"))
        assertEquals(listOf("1", "3", "2"), orderCustomers(names, emptyList(), CustomerOrdering.MOST_ACTIVE).map { it.id })
        assertEquals(listOf("1", "3", "2"), orderCustomers(names, emptyList(), CustomerOrdering.HIGHEST).map { it.id })
    }
}

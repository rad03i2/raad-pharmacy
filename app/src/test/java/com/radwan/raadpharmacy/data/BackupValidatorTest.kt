package com.radwan.raadpharmacy.data

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupValidatorTest {
    private fun customer(
        id: String = "c1",
        openingDebt: Long = 0L
    ) = Customer(
        id = id,
        name = "زبون",
        openingDebt = openingDebt,
        createdAt = 1_000L
    )

    @Test
    fun validBackupPayload_passesValidation() {
        val customer = customer(openingDebt = 2_000L)
        val payload = BackupPayload(
            customers = listOf(customer),
            entries = listOf(
                LedgerEntry(
                    id = "d1",
                    customerId = customer.id,
                    type = EntryType.DEBT,
                    amount = 3_000L,
                    createdAt = 2_000L
                ),
                LedgerEntry(
                    id = "p1",
                    customerId = customer.id,
                    type = EntryType.PAYMENT,
                    amount = 4_000L,
                    createdAt = 3_000L
                )
            ),
            createdAt = 4_000L,
            schemaVersion = BackupJson.SCHEMA_VERSION
        )

        BackupValidator.validate(payload)
        assertTrue(LedgerRules.isChronologicallyValid(customer, payload.entries))
    }

    @Test
    fun duplicateCustomerIds_areRejected() {
        val payload = BackupPayload(
            customers = listOf(customer("same"), customer("same")),
            entries = emptyList(),
            createdAt = 2_000L,
            schemaVersion = 1
        )

        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(payload)
        }
    }

    @Test
    fun duplicateEntryIds_areRejected() {
        val customer = customer()
        val payload = BackupPayload(
            customers = listOf(customer),
            entries = listOf(
                LedgerEntry("same", customer.id, EntryType.DEBT, 1_000L, createdAt = 2_000L),
                LedgerEntry("same", customer.id, EntryType.DEBT, 1_000L, createdAt = 3_000L)
            ),
            createdAt = 4_000L,
            schemaVersion = 1
        )

        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(payload)
        }
    }

    @Test
    fun orphanEntry_isRejected() {
        val payload = BackupPayload(
            customers = listOf(customer()),
            entries = listOf(
                LedgerEntry(
                    id = "e1",
                    customerId = "missing",
                    type = EntryType.DEBT,
                    amount = 1_000L,
                    createdAt = 2_000L
                )
            ),
            createdAt = 3_000L,
            schemaVersion = 1
        )

        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(payload)
        }
    }

    @Test
    fun backupWithChronologicalOverpayment_isRejected() {
        val customer = customer(openingDebt = 1_000L)
        val payload = BackupPayload(
            customers = listOf(customer),
            entries = listOf(
                LedgerEntry(
                    id = "p1",
                    customerId = customer.id,
                    type = EntryType.PAYMENT,
                    amount = 1_001L,
                    createdAt = 2_000L
                )
            ),
            createdAt = 3_000L,
            schemaVersion = 1
        )

        assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.validate(payload)
        }
    }
}

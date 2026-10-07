package com.radwan.raadpharmacy.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupCompatibilityTest {

    @Test
    fun modernBackup_roundTripPreservesLedgerData() {
        val customer = Customer(
            id = "c-1",
            name = "أحمد محمد",
            phone = "07701234567",
            area = "حي النور",
            address = "قرب الجامع",
            openingDebt = 25_000L,
            notes = "ملاحظة",
            createdAt = 1_000L
        )
        val entries = listOf(
            LedgerEntry(
                id = "d-1",
                customerId = customer.id,
                type = EntryType.DEBT,
                amount = 50_000L,
                bottles = 2,
                bottlePrice = 25_000L,
                details = "أدوية",
                createdAt = 2_000L
            ),
            LedgerEntry(
                id = "p-1",
                customerId = customer.id,
                type = EntryType.PAYMENT,
                amount = 30_000L,
                createdAt = 3_000L
            )
        )

        val raw = BackupJson.encode(
            customers = listOf(customer),
            entries = entries,
            appVersion = "2.6.0-rc1",
            createdAt = 4_000L
        )
        val restored = BackupValidator.parseValid(raw)

        assertEquals(listOf(customer), restored.customers)
        assertEquals(entries, restored.entries)
        assertEquals(4_000L, restored.createdAt)
        assertEquals(BackupJson.SCHEMA_VERSION, restored.schemaVersion)
        assertEquals(45_000L, customerBalance(customer, restored.entries))
    }

    @Test
    fun futureBackupSchema_isRejectedInsteadOfGuessing() {
        val raw = JSONObject().apply {
            put("backupFormat", BackupJson.FORMAT)
            put("schemaVersion", BackupJson.SCHEMA_VERSION + 1)
            put("createdAt", 1_000L)
            put("customers", JSONArray())
            put("entries", JSONArray())
        }.toString()

        val error = assertThrows(IllegalArgumentException::class.java) {
            BackupValidator.parseValid(raw)
        }
        assertTrue(error.message.orEmpty().contains("أحدث"))
    }

    @Test
    fun legacyBackup_withoutModernHeaderRemainsImportable() {
        val raw = JSONObject().apply {
            put("exportedAt", 9_000L)
            put("customers", JSONArray().put(JSONObject().apply {
                put("id", "legacy-c")
                put("name", "زبون قديم")
                put("phone", "")
                put("area", "الجامعة")
                put("address", "")
                put("openingDebt", 5_000L)
                put("notes", "")
                put("createdAt", 1_000L)
            }))
            put("entries", JSONArray().put(JSONObject().apply {
                put("id", "legacy-d")
                put("customerId", "legacy-c")
                put("type", EntryType.DEBT.name)
                put("amount", 10_000L)
                put("bottles", 1)
                put("bottlePrice", 10_000L)
                put("details", "")
                put("createdAt", 2_000L)
            }))
        }.toString()

        val payload = BackupValidator.parseValid(raw)

        assertEquals(1, payload.customers.size)
        assertEquals(1, payload.entries.size)
        assertEquals(0, payload.schemaVersion)
        assertEquals(9_000L, payload.createdAt)
        assertEquals(15_000L, customerBalance(payload.customers.single(), payload.entries))
    }

    @Test
    fun realisticLargeBackup_roundTripsWithoutDroppingRows() {
        val customers = (1..200).map { index ->
            Customer(
                id = "c-" + index,
                name = "زبون " + index,
                area = "منطقة " + ((index % 10) + 1),
                openingDebt = 1_000L,
                createdAt = 1_000L + index
            )
        }
        val entries = customers.flatMapIndexed { index, customer ->
            listOf(
                LedgerEntry(
                    id = "d-" + index,
                    customerId = customer.id,
                    type = EntryType.DEBT,
                    amount = 10_000L,
                    bottles = 1,
                    bottlePrice = 10_000L,
                    createdAt = 10_000L + index * 2L
                ),
                LedgerEntry(
                    id = "p-" + index,
                    customerId = customer.id,
                    type = EntryType.PAYMENT,
                    amount = 4_000L,
                    createdAt = 10_001L + index * 2L
                )
            )
        }

        val payload = BackupValidator.parseValid(
            BackupJson.encode(
                customers = customers,
                entries = entries,
                appVersion = "2.6.0-rc1",
                createdAt = 50_000L
            )
        )

        assertEquals(200, payload.customers.size)
        assertEquals(400, payload.entries.size)
        assertTrue(payload.customers.all { customer ->
            customerBalance(customer, payload.entries) == 7_000L
        })
    }
}

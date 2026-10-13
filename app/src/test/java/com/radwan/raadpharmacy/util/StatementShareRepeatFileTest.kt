package com.radwan.raadpharmacy.util

import com.radwan.raadpharmacy.data.Customer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatementShareRepeatFileTest {
    @Test fun eachShareGetsNewOpaqueFilePathEvenForSameCustomerAndSnapshot() {
        val customer = Customer(id = "sample", name = "زبون تجريبي")
        val snapshot = StatementSnapshot(customer, emptyList(), 50000, 50000, 0, generatedAt = 1000)
        val names = (1..4).map { StatementDocumentRenderer.fileName(snapshot, "png") }
        assertEquals(4, names.distinct().size)
        assertTrue(names.all { it.startsWith("statement-1000-") && it.endsWith(".png") })
        assertFalse(names.any { it.contains(customer.name) || it.contains(customer.id) })
    }
}

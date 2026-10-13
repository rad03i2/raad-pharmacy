package com.radwan.raadpharmacy.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoFixturesTest {
    @Test fun marketingDataIsLocalFixedAndCannotContainLivePhoneNumbers() {
        assertEquals(10, DemoFixtures.customers.size)
        assertEquals(10, DemoFixtures.customers.map { it.name }.distinct().size)
        assertEquals(listOf("رعد", "أحمد", "فؤاد"), DemoFixtures.staff)
        assertTrue(DemoFixtures.customers.all { it.due >= 0 && it.name.isNotBlank() })
        assertTrue(DemoFixtures.customers.all { it.avatar in 0..9 })
    }
}

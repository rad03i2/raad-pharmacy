package com.radwan.raadpharmacy.util

import com.radwan.raadpharmacy.data.Customer
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomerSearchTest {

    private val customers = listOf(
        Customer(
            id = "1",
            name = "أحمد علي",
            phone = "0770 123 4567",
            area = "الزهور",
            address = "شارع الجامعة",
            createdAt = 100L
        ),
        Customer(
            id = "2",
            name = "احمد محمود",
            phone = "0750-999-1111",
            area = "الموصل الجديدة",
            address = "حي الجامعة",
            createdAt = 200L
        ),
        Customer(
            id = "3",
            name = "فؤاد سالم",
            phone = "07801230000",
            area = "الحدباء",
            address = "شارع النور",
            createdAt = 300L
        )
    )

    @Test
    fun arabicHamzaDifferencesStillMatch() {
        val result = CustomerSearch.rank(
            customers = customers,
            query = "احمد",
            balance = { 0L },
            recentAt = { it.createdAt }
        )

        assertEquals(listOf("1", "2"), result.take(2).map { it.id }.sorted())
    }

    @Test
    fun arabicIndicPhoneDigitsMatchEnglishPhone() {
        val result = CustomerSearch.rank(
            customers = customers,
            query = "٠٧٧٠١٢٣",
            balance = { 0L },
            recentAt = { it.createdAt }
        )

        assertEquals("1", result.first().id)
    }

    @Test
    fun addressAndAreaAreSearchable() {
        val address = CustomerSearch.rank(customers, "الجامعة", { 0L }, { it.createdAt })
        val area = CustomerSearch.rank(customers, "الحدباء", { 0L }, { it.createdAt })

        assertEquals(setOf("1", "2"), address.take(2).map { it.id }.toSet())
        assertEquals("3", area.first().id)
    }

    @Test
    fun exactNameRanksBeforePartialName() {
        val result = CustomerSearch.rank(
            customers = customers,
            query = "فؤاد سالم",
            balance = { 0L },
            recentAt = { it.createdAt }
        )

        assertEquals("3", result.first().id)
    }
}

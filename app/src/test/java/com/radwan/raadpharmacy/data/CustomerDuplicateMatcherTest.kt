package com.radwan.raadpharmacy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerDuplicateMatcherTest {
    private fun customer(id: String, name: String, phone: String? = null) =
        Customer(id = id, name = name, phone = phone)

    @Test fun matchesArabicNameIgnoringDiacriticsAndAlefVariants() {
        val existing = customer("1", "أَحْمَد محمد")
        val matches = findCustomerDuplicates("احمد محمد", "", listOf(existing))
        assertEquals(listOf("1"), matches.sameName.map { it.id })
        assertTrue(matches.samePhone.isEmpty())
    }

    @Test fun warnsAboutSmallNameTyposWithoutMatchingVeryShortNamesFuzzily() {
        val existing = listOf(customer("1", "عبدالرحمن"), customer("2", "حسن"))
        assertEquals(listOf("1"), findCustomerDuplicates("عبدالرحمان", "", existing).sameName.map { it.id })
        assertTrue(findCustomerDuplicates("حسين", "", existing).sameName.isEmpty())
    }

    @Test fun matchesIraqiPhoneWithLocalAndInternationalPrefixes() {
        val existing = customer("1", "سالم", "07701234567")
        val matches = findCustomerDuplicates("شخص آخر", "+964 770 123 4567", listOf(existing))
        assertEquals(listOf("1"), matches.samePhone.map { it.id })
    }

    @Test fun emptyPhoneDoesNotMarkAllCustomersAsDuplicates() {
        val matches = findCustomerDuplicates("شخص جديد", "", listOf(customer("1", "سالم", null)))
        assertFalse(matches.hasMatches)
    }

    @Test fun nameAndPhoneMatchesAreReportedSeparately() {
        val existing = listOf(customer("1", "محمد علي", "07700000001"), customer("2", "شخص آخر", "07700000002"))
        val matches = findCustomerDuplicates("محمد علي", "07700000002", existing)
        assertEquals(listOf("1"), matches.sameName.map { it.id })
        assertEquals(listOf("2"), matches.samePhone.map { it.id })
    }
}

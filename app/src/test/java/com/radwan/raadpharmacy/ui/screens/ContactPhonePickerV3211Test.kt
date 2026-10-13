package com.radwan.raadpharmacy.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactPhonePickerV3211Test {
    @Test fun localAndInternationalIraqNumbersNormalizeLikeCustomerCreation() {
        assertEquals("07701234567", normalizePickedPhone("0770 123 4567"))
        assertEquals("07701234567", normalizePickedPhone("+964 770 123 4567"))
        assertEquals("07701234567", normalizePickedPhone("00964-770-123-4567"))
        assertEquals("07701234567", normalizePickedPhone("7701234567"))
    }
}

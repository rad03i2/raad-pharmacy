package com.radwan.raadpharmacy.util

import org.junit.Assert.*
import org.junit.Test

class IraqiWhatsappPhoneTest {
    @Test fun acceptsLocalIraqiMobile() {
        assertEquals("9647701234567", validatedIraqiWhatsappPhone("07701234567"))
    }
    @Test fun acceptsInternationalIraqiMobile() {
        assertEquals("9647701234567", validatedIraqiWhatsappPhone("+964 770 123 4567"))
        assertEquals("9647701234567", validatedIraqiWhatsappPhone("9647701234567"))
        assertEquals("9647701234567", validatedIraqiWhatsappPhone("00964 770 123 4567"))
    }
    @Test fun acceptsArabicAndPersianDigitsWithoutChangingTheSavedPhone() {
        assertEquals("9647701234567", validatedIraqiWhatsappPhone("٠٧٧٠١٢٣٤٥٦٧"))
        assertEquals("9647701234567", validatedIraqiWhatsappPhone("۰۷۷۰۱۲۳۴۵۶۷"))
        assertEquals("9647701234567", validatedIraqiWhatsappPhone("+٩٦٤ ٧٧٠ ١٢٣ ٤٥٦٧"))
    }
    @Test fun refusesBlankLandlineAndForeignNumber() {
        assertNull(validatedIraqiWhatsappPhone(""))
        assertNull(validatedIraqiWhatsappPhone("0753"))
        assertNull(validatedIraqiWhatsappPhone("0712345678"))
        assertNull(validatedIraqiWhatsappPhone("+447701234567"))
        assertNull(validatedIraqiWhatsappPhone("0044 7701234567"))
    }
}

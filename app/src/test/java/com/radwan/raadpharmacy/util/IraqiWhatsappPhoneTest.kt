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
    }
    @Test fun refusesBlankLandlineAndForeignNumber() {
        assertNull(validatedIraqiWhatsappPhone(""))
        assertNull(validatedIraqiWhatsappPhone("0753"))
        assertNull(validatedIraqiWhatsappPhone("0712345678"))
        assertNull(validatedIraqiWhatsappPhone("+447701234567"))
    }
}
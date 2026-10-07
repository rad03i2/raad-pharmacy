package com.radwan.raadpharmacy.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class FinancialFeedbackSettingsTest {

    @Test
    fun operationSoundAlwaysUsesFixedPixabaySound() {
        assertEquals(
            OperationSoundPreset.PIXABAY_OPERATION,
            OperationSoundPreset.fromStorage(null)
        )
        assertEquals(
            OperationSoundPreset.PIXABAY_OPERATION,
            OperationSoundPreset.fromStorage("cash_register")
        )
        assertEquals(1, OperationSoundPreset.entries.size)
    }

    @Test
    fun notificationSoundAlwaysUsesFixedPixabaySound() {
        assertEquals(
            NotificationSoundPreset.PIXABAY_NOTIFICATION,
            NotificationSoundPreset.fromStorage(null)
        )
        assertEquals(
            NotificationSoundPreset.PIXABAY_NOTIFICATION,
            NotificationSoundPreset.fromStorage("classic_note")
        )
        assertEquals(1, NotificationSoundPreset.entries.size)
    }
}

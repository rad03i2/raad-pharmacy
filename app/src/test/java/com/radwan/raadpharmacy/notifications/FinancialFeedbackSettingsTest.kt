package com.radwan.raadpharmacy.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class FinancialFeedbackSettingsTest {

    @Test
    fun operationSound_defaultsToCashRegister() {
        assertEquals(
            OperationSoundPreset.CASH_REGISTER,
            OperationSoundPreset.fromStorage(null)
        )
        assertEquals(
            OperationSoundPreset.CASH_REGISTER,
            OperationSoundPreset.fromStorage("unknown")
        )
    }

    @Test
    fun operationSound_restoresPersistedPreset() {
        assertEquals(
            OperationSoundPreset.COIN_CASCADE,
            OperationSoundPreset.fromStorage("coin_cascade")
        )
        assertEquals(
            OperationSoundPreset.POS_PREMIUM,
            OperationSoundPreset.fromStorage("pos_premium")
        )
    }

    @Test
    fun notificationSound_defaultsToCashPing() {
        assertEquals(
            NotificationSoundPreset.CASH_PING,
            NotificationSoundPreset.fromStorage(null)
        )
        assertEquals(
            NotificationSoundPreset.CASH_PING,
            NotificationSoundPreset.fromStorage("invalid")
        )
    }

    @Test
    fun notificationSound_restoresPersistedPreset() {
        assertEquals(
            NotificationSoundPreset.SOFT_BELL,
            NotificationSoundPreset.fromStorage("soft_bell")
        )
        assertEquals(
            NotificationSoundPreset.DOUBLE_CHIME,
            NotificationSoundPreset.fromStorage("double_chime")
        )
    }
}

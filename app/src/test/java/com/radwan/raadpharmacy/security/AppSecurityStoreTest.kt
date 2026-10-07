package com.radwan.raadpharmacy.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppSecurityStoreTest {
    private lateinit var context: Context
    @Before fun reset() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("gas_ledger_security", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun failedAttemptsPersistAndCorrectPinWaitsUntilCooldownExpires() {
        val store = AppSecurityStore(context)
        assertTrue(store.setPin("5824").success)
        val now = System.currentTimeMillis()
        repeat(5) { assertFalse(store.verifyPin("1928", now)) }
        val restarted = AppSecurityStore(context)
        assertEquals(30L, restarted.retryAfterSeconds(now))
        assertFalse(restarted.verifyPin("5824", now + 29_999L))
        assertTrue(restarted.verifyPin("5824", now + 30_000L))
        assertEquals(0L, restarted.retryAfterSeconds(now + 30_000L))
        assertFalse(restarted.verifyPin("1928", now + 30_001L))
        assertEquals(0L, restarted.retryAfterSeconds(now + 30_001L))
    }

    @Test fun changingAndDisablingPinCannotBypassAttemptLimit() {
        val store = AppSecurityStore(context)
        store.setPin("5824")
        repeat(5) { assertFalse(store.verifyPin("1928")) }
        assertFalse(store.changePin("5824", "7391").success)
        assertFalse(store.disablePin("5824").success)
        assertTrue(store.isPinEnabled())
    }

    @Test fun pinIsHashedAndExistingCredentialsSurviveStoreRecreation() {
        AppSecurityStore(context).setPin("5824")
        val prefs = context.getSharedPreferences("gas_ledger_security", Context.MODE_PRIVATE)
        assertFalse(prefs.all.values.any { it.toString() == "5824" })
        assertTrue(AppSecurityStore(context).verifyPin("5824"))
    }
}

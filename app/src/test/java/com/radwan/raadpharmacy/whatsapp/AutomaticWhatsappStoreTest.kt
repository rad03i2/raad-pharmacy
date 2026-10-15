package com.radwan.raadpharmacy.whatsapp

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AutomaticWhatsappStoreTest {
    private lateinit var context: Context
    private lateinit var store: AutomaticWhatsappStore

    @Before fun clean() {
        context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("raad_auto_whatsapp_v1", Context.MODE_PRIVATE).edit().clear().commit()
        store = AutomaticWhatsappStore(context)
    }

    @Test fun automationIsDisabledByDefaultAndNeedsAnExplicitSender() {
        assertFalse(store.enabled())
        assertNull(store.sender())
        assertThrows(IllegalArgumentException::class.java) { store.setEnabled(true) }
        store.setEnabled(true, "9647700000000", "user-a")
        assertTrue(AutomaticWhatsappStore(context).enabled())
        assertEquals("9647700000000", store.sender())
        assertEquals("user-a", store.userId())
        store.setEnabled(false)
        assertFalse(AutomaticWhatsappStore(context).enabled())
    }

    @Test fun consentIsBoundToBothCustomerAndPhoneAndCanBeRevoked() {
        store.approve("one", "9647700000001", true)
        assertTrue(store.approved("one", "9647700000001"))
        assertFalse(store.approved("one", "9647700000002"))
        assertFalse(store.approved("two", "9647700000001"))
        store.approve("one", "9647700000001", false)
        assertFalse(store.approved("one", "9647700000001"))
    }

    @Test fun aReservedCaseIsDurableAndNeverResentAfterUncertainOutcome() {
        val key = "d".repeat(64)
        assertNull(store.reserve(key, "9647700000001"))
        store.setEnabled(true, "9647700000000", "user-a")
        val id = requireNotNull(store.reserve(key, "9647700000001"))
        assertEquals(id, AutomaticWhatsappStore(context).logs().first().id)
        assertNull(AutomaticWhatsappStore(context).reserve(key, "9647700000001"))
        store.record(id, "uncertain")
        assertEquals("uncertain", AutomaticWhatsappStore(context).stateFor(key, "9647700000001"))
        store.setEnabled(false)
        assertNull(store.reserve(key, "9647700000001"))
        store.setEnabled(true, "9647700000000", "user-a")
        assertNull(store.reserve(key, "9647700000001"))
    }

    @Test fun differentWorkerInstancesCannotReserveTheSameReminderTwice() {
        store.setEnabled(true, "9647700000000", "user-a")
        val key = "a".repeat(64)
        val results = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (1..8).map {
            Thread {
                start.await()
                results.add(AutomaticWhatsappStore(context).reserve(key, "9647700000001"))
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(1, results.count { it != null })
    }

    @Test fun reservationEnforcesTenDailyAttemptsEvenWithDifferentCases() {
        store.setEnabled(true, "9647700000000", "user-a")
        for (i in 0 until 10) {
            val hash = i.toString(16).padStart(64, '0')
            assertNotNull(store.reserve(hash, "9647700000001"))
        }
        assertNull(store.reserve("f".repeat(64), "9647700000001"))
        assertEquals(10, store.logs().size)
    }
}

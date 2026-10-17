package com.radwan.raadpharmacy.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ReminderDefaultsTest {
    @Test fun shortRemindersStartOffAndDoNotAlterOrdinaryReminderPreferences() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("raad_short_reminders_v23",Context.MODE_PRIVATE).edit().clear().commit()
        val ordinary=ReminderStore(context)
        ordinary.setEnabled(true);ordinary.setFrequency(ReminderFrequency.WEEKLY)
        val before=ordinary.state()
        val short=ShortReminderStore(context)
        assertFalse(short.enabled());short.setEnabled(true)
        assertTrue(ShortReminderStore(context).enabled());assertEquals(before,ordinary.state())
        short.setEnabled(false);assertFalse(short.enabled());assertEquals(before,ordinary.state())
    }
    @Test fun weeklyDefaultIsAppliedOnceAndLaterChoiceIsPreserved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("gas_ledger_reminders",Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enabled",false).putString("frequency","daily").putInt("minimum_age_days",30).commit()
        val store = ReminderStore(context)
        assertTrue(store.state().enabled)
        assertEquals(ReminderFrequency.WEEKLY,store.state().frequency)
        assertEquals(7,store.state().minimumAgeDays)
        store.setEnabled(false); store.setFrequency(ReminderFrequency.DAILY); store.setMinimumAgeDays(15)
        val reopened = ReminderStore(context).state()
        assertFalse(reopened.enabled)
        assertEquals(ReminderFrequency.DAILY,reopened.frequency)
        assertEquals(15,reopened.minimumAgeDays)
    }
}

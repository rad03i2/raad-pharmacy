package com.radwan.raadpharmacy.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.radwan.raadpharmacy.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ShortReminderWorkerTest {
    private lateinit var app: Application
    private lateinit var entry: LedgerEntry
    @Before fun fixture()=runBlocking {
        app=ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.getSharedPreferences("raad_cloud_auth",Context.MODE_PRIVATE).edit().putBoolean("has_offline_session",true).commit()
        app.getSharedPreferences("raad_short_reminders_v23",Context.MODE_PRIVATE).edit().clear().commit()
        ShortReminderStore(app).setEnabled(true)
        entry=LedgerEntry("short-debt","short-customer",EntryType.DEBT,1000,
            createdAt=System.currentTimeMillis()-600_000L,repaymentDays=-5)
        PharmacyLedgerDatabase.get(app).dao().replaceAll(listOf(Customer("short-customer","synthetic",createdAt=entry.createdAt-1000).toEntity()),listOf(entry.toEntity()))
        app.getSystemService(NotificationManager::class.java).cancelAll()
    }
    private suspend fun runWorker() {
        val due=RepaymentTerms.shortDueAt(entry)!!
        TestListenableWorkerBuilder<ShortReminderWorker>(app).setInputData(workDataOf(
            "entry" to entry.id,"due" to due,"key" to "${entry.id}:$due")).build().doWork()
    }
    private fun count()=Shadows.shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.size
    @Test fun dueDebtActuallyNotifiesOnceAndRetainsFinancialRecords()=runBlocking {
        runWorker();assertEquals(1,count());runWorker();assertEquals(1,count())
        val dao=PharmacyLedgerDatabase.get(app).dao()
        assertEquals(1,dao.entryCount());assertEquals(entry,dao.getEntryById(entry.id)!!.toModel())
    }
    @Test fun settledDebtNeverSendsShortNotification()=runBlocking {
        PharmacyLedgerDatabase.get(app).dao().insertEntry(LedgerEntry("paid",entry.customerId,EntryType.PAYMENT,1000,createdAt=entry.createdAt+1).toEntity())
        runWorker();assertEquals(0,count())
    }
    @Test fun disabledFeatureOrSignedOutSessionDoesNotNotify()=runBlocking {
        ShortReminderStore(app).setEnabled(false);runWorker();assertEquals(0,count())
        ShortReminderStore(app).setEnabled(true)
        app.getSharedPreferences("raad_cloud_auth",Context.MODE_PRIVATE).edit().putBoolean("has_offline_session",false).commit()
        runWorker();assertEquals(0,count())
    }
    @Test fun editedDueDateDoesNotFireTheOldWorkRequest()=runBlocking {
        PharmacyLedgerDatabase.get(app).dao().updateEntry(entry.copy(repaymentDays=-120).toEntity())
        runWorker();assertEquals(0,count())
    }
}

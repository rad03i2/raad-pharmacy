package com.radwan.raadpharmacy.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.security.AppSecurityStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LedgerNotificationChannelsTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        context.getSharedPreferences(
            "gas_ledger_financial_feedback",
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        AppSecurityStore(context).setHideAmounts(false)
    }

    @Test
    fun fixedChannelsUseBundledSoundWithoutInternet() {
        LedgerNotificationType.entries.forEach { type ->
            val id = LedgerNotificationChannels.ensure(context, type)
            val channel = manager.getNotificationChannel(id)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
            assertTrue(channel.sound.toString().contains("iphone_notification_myinstants"))
        }
    }

    @Test
    fun legacyBlockedChannelRemainsBlocked() {
        manager.createNotificationChannel(
            NotificationChannel(
                LedgerNotificationType.REMINDER.legacyId,
                "blocked",
                NotificationManager.IMPORTANCE_NONE
            )
        )
        val id = LedgerNotificationChannels.ensure(
            context,
            LedgerNotificationType.REMINDER
        )
        assertEquals(
            NotificationManager.IMPORTANCE_NONE,
            manager.getNotificationChannel(id).importance
        )
    }

    @Test
    fun popupSettingsOpenFixedOperationChannel() {
        val intent = LedgerNotificationChannels.settingsIntent(context)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(
            context.packageName,
            intent.getStringExtra(Settings.EXTRA_APP_PACKAGE)
        )
        assertEquals(
            LedgerNotificationChannels.channelId(
                LedgerNotificationType.OPERATION,
                NotificationSoundPreset.PIXABAY_NOTIFICATION
            ),
            intent.getStringExtra(Settings.EXTRA_CHANNEL_ID)
        )
    }

    @Test
    fun everyFinancialOperationHasAccountActionAndPrivatePublicVersion() {
        FinancialOperationKind.entries.forEach { kind ->
            val notification = FinancialOperationFeedback.buildNotification(
                context,
                FinancialOperationReceipt(kind, "c1", "أحمد", 5000, 10000)
            )
            assertEquals(NotificationCompat.PRIORITY_HIGH, notification.priority)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals("عرض الحساب", notification.actions.single().title)
            assertEquals(
                notification.contentIntent,
                notification.actions.single().actionIntent
            )
            assertFalse(
                notification.publicVersion.extras
                    .getCharSequence(Notification.EXTRA_TEXT)
                    .toString()
                    .contains("أحمد")
            )
        }
    }

    @Test
    fun hiddenAmountsAreNotExposedInPopup() {
        AppSecurityStore(context).setHideAmounts(true)
        val notification = FinancialOperationFeedback.buildNotification(
            context,
            FinancialOperationReceipt(
                FinancialOperationKind.DEBT,
                "c1",
                "أحمد",
                5000,
                10000
            )
        )
        val body = notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)
            .toString()
        assertTrue(body.contains("أحمد"))
        assertFalse(body.contains("5,000"))
        assertFalse(body.contains("10,000"))
    }

    @Test
    fun eachSavedOperationGetsSeparateNotificationId() {
        val ids = (1..100).map { FinancialOperationFeedback.nextNotificationId() }
        assertEquals(ids.size, ids.toSet().size)
    }
}

package com.radwan.raadpharmacy.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.security.AppSecurityStore
import org.junit.Assert.*
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

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        context.getSharedPreferences("gas_ledger_financial_feedback", Context.MODE_PRIVATE).edit().clear().commit()
        AppSecurityStore(context).setHideAmounts(false)
    }

    @Test fun selectedSound_isStoredIndependentlyAndUsedByBothNotificationTypes() {
        val store = FinancialFeedbackStore(context)
        OperationSoundPreset.entries.forEach { preset ->
            store.setOperationSound(preset)
            assertEquals(preset, FinancialFeedbackStore(context).state().operationSound)
        }
        NotificationSoundPreset.entries.forEach { preset ->
            store.setNotificationSound(preset)
            assertEquals(preset, FinancialFeedbackStore(context).state().notificationSound)
            LedgerNotificationType.entries.forEach { type ->
                val id = LedgerNotificationChannels.ensure(context, type)
                val channel = manager.getNotificationChannel(id)
                assertEquals(soundResourceUri(context, preset.resourceId), channel.sound)
                assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
                assertEquals(AudioAttributes.USAGE_NOTIFICATION, channel.audioAttributes.usage)
            }
        }
        assertEquals(OperationSoundPreset.REBOUND, store.state().operationSound)
    }

    @Test fun legacyDefaultChannel_upgradesToHeadsUpCapableChannel() {
        manager.createNotificationChannel(NotificationChannel(LedgerNotificationType.OPERATION.legacyId,
            "old", NotificationManager.IMPORTANCE_DEFAULT))
        val id = FinancialOperationFeedback.ensureChannel(context)
        assertNotEquals(LedgerNotificationType.OPERATION.legacyId, id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(id).importance)
    }

    @Test fun legacyBlockedChannel_remainsBlocked() {
        manager.createNotificationChannel(NotificationChannel(LedgerNotificationType.REMINDER.legacyId,
            "blocked", NotificationManager.IMPORTANCE_NONE))
        val id = LedgerNotificationChannels.ensure(context, LedgerNotificationType.REMINDER)
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(id).importance)
    }

    @Test fun existingMutedSelectedChannel_isNotOverwritten() {
        val id = LedgerNotificationChannels.channelId(LedgerNotificationType.OPERATION, NotificationSoundPreset.NOTE)
        manager.createNotificationChannel(NotificationChannel(id, "muted", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
        })
        LedgerNotificationChannels.ensure(context, LedgerNotificationType.OPERATION, NotificationSoundPreset.NOTE)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(id).importance)
        assertNull(manager.getNotificationChannel(id).sound)
    }

    @Test fun changingSound_usesAnotherChannelWithoutMutatingPreviousSound() {
        val store = FinancialFeedbackStore(context)
        store.setNotificationSound(NotificationSoundPreset.GLASS)
        val old = FinancialOperationFeedback.ensureChannel(context)
        store.setNotificationSound(NotificationSoundPreset.TRI_TONE)
        val new = FinancialOperationFeedback.ensureChannel(context)
        assertNotEquals(old, new)
        assertEquals(soundResourceUri(context, NotificationSoundPreset.GLASS.resourceId), manager.getNotificationChannel(old).sound)
        assertEquals(soundResourceUri(context, NotificationSoundPreset.TRI_TONE.resourceId), manager.getNotificationChannel(new).sound)
    }

    @Test fun popupSettings_openCurrentlySelectedChannel() {
        FinancialFeedbackStore(context).setNotificationSound(NotificationSoundPreset.REBOUND)
        val intent = LedgerNotificationChannels.settingsIntent(context)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(LedgerNotificationChannels.channelId(LedgerNotificationType.OPERATION, NotificationSoundPreset.REBOUND),
            intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test fun everyFinancialOperation_hasAccountActionAndPrivatePublicVersion() {
        FinancialOperationKind.entries.forEach { kind ->
            val notification = FinancialOperationFeedback.buildNotification(context,
                FinancialOperationReceipt(kind, "c1", "أحمد", 5000, 10000))
            assertEquals(NotificationCompat.PRIORITY_HIGH, notification.priority)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals(0, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
            assertEquals("عرض الحساب", notification.actions.single().title)
            assertEquals(notification.contentIntent, notification.actions.single().actionIntent)
            assertFalse(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("أحمد"))
            assertFalse(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("5,000"))
        }
    }

    @Test fun hiddenAmounts_areNotExposedInPopup() {
        AppSecurityStore(context).setHideAmounts(true)
        val notification = FinancialOperationFeedback.buildNotification(context,
            FinancialOperationReceipt(FinancialOperationKind.DEBT, "c1", "أحمد", 5000, 10000))
        val body = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(body.contains("أحمد"))
        assertFalse(body.contains("5,000"))
        assertFalse(body.contains("10,000"))
    }

    @Test fun eachSavedOperation_getsSeparateNotificationId() {
        val ids = (1..100).map { FinancialOperationFeedback.nextNotificationId() }
        assertEquals(ids.size, ids.toSet().size)
    }
}

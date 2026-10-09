package com.radwan.raadpharmacy.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.cloud.CloudExternalNotification
import com.radwan.raadpharmacy.cloud.CloudNotificationCenter
import com.radwan.raadpharmacy.security.AppSecurityStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudNotificationCenterTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        AppSecurityStore(context).setHideAmounts(false)
    }

    @Test fun channelHasOfflineSoundAndPushNotificationsAreIndependent() = runTest {
        val rows = (1..3).map { CloudExternalNotification("event-$it", "دين", "أحمد • $it") }
        CloudNotificationCenter.postBatch(context, rows)
        val channel = manager.getNotificationChannel(CloudNotificationCenter.CHANNEL_ALERT)
        assertNotNull(channel.sound)
        assertTrue(channel.sound.toString().contains("pixabay_notification_037"))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(3, manager.activeNotifications.size)
        manager.activeNotifications.forEach {
            assertFalse(it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            assertEquals(CloudNotificationCenter.CHANNEL_ALERT, it.notification.channelId)
            assertNotNull(it.notification.contentIntent)
        }
    }

    @Test fun identicalEventIdUpdatesExistingNotification() {
        CloudNotificationCenter.post(context, "دين", "أحمد", eventId = "same")
        CloudNotificationCenter.post(context, "دين", "أحمد", eventId = "same")
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun privateNotificationRedactsLockScreenAndHideAmountsRedactsFullContent() {
        CloudNotificationCenter.post(context, "دين أحمد", "المبلغ 5000", eventId = "private")
        val notification = manager.activeNotifications.single().notification
        val privateId = manager.activeNotifications.single().id
        assertNotNull(notification.publicVersion)
        assertFalse(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("5000"))
        AppSecurityStore(context).setHideAmounts(true)
        CloudNotificationCenter.post(context, "دين أحمد", "المبلغ 5000", eventId = "hidden")
        val hidden = manager.activeNotifications.first { it.id != privateId }.notification
        assertEquals("صيدلية رعد", hidden.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(hidden.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("5000"))
    }
    @Test fun messageNotificationOpensSettingsAndKeepsPrivateTextOffTheLockScreen() {
        CloudNotificationCenter.post(context,"رسالة من أحمد","رسالة خاصة",eventId="message",
            openSettings=true,isMessage=true)
        val notification=manager.activeNotifications.single().notification
        assertTrue(shadowOf(notification.contentIntent).savedIntent
            .getBooleanExtra(com.radwan.raadpharmacy.MainActivity.EXTRA_OPEN_SETTINGS,false))
        assertEquals("رسالة خاصة",notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("رسالة خاصة"))
    }

}

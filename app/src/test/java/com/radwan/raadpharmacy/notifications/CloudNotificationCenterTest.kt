package com.radwan.raadpharmacy.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.cloud.CloudExternalNotification
import com.radwan.raadpharmacy.cloud.CloudNotificationCenter
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
}

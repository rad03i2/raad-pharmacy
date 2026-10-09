package com.radwan.raadpharmacy.cloud

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.security.AppSecurityStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundPushDeliveryTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private val event = CloudNotificationEventRow("background-event", "pharmacy", actorDisplayName = "أحمد",
        actorDeviceId = "other-device", eventType = "DEBT_CREATED", amount = 5000.0,
        createdAt = "2026-10-09T00:00:00Z")

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        listOf("raad_cloud_device", "raad_cloud_notification_inbox_v1", "raad_background_work").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        context.getSharedPreferences("raad_background_work", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
        CloudDeviceStore(context).savePushIdentity("recipient", "pharmacy")
        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AppSecurityStore(context).setHideAmounts(false)
    }

    @Test fun displaysInBackgroundWithoutWaitingForAuthOrNetworkAndPersistsDeduplication() = runTest {
        val inbox = CloudNotificationInbox(context)
        assertTrue(inbox.deliverPushImmediately(event))
        assertEquals(1, manager.activeNotifications.size)
        assertTrue(CloudNotificationInbox(context).deliverPushImmediately(event))
        assertEquals(1, manager.activeNotifications.size)
        assertTrue(inbox.wasDelivered(event.id))
    }

    @Test fun rejectsOtherPharmacyAndOtherRecipient() = runTest {
        val inbox = CloudNotificationInbox(context)
        assertTrue(inbox.deliverPushImmediately(event.copy(pharmacyId = "other-pharmacy")))
        assertTrue(inbox.deliverPushImmediately(event.copy(id = "private", eventType = "TEAM_ALERT", recipientUserId = "third")))
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test fun missingIdentityDefersToDurableWorkerWithoutShowingPrivateData() = runTest {
        CloudDeviceStore(context).clearPushIdentity()
        assertFalse(CloudNotificationInbox(context).deliverPushImmediately(event))
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test fun deniedPermissionRetainsTheEventForRetry() = runTest {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val inbox = CloudNotificationInbox(context)
        assertFalse(inbox.deliverPushImmediately(event))
        assertFalse(inbox.wasDelivered(event.id))
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(inbox.deliverPushImmediately(event))
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun logoutStopsDeliveryAndOwnDeviceDoesNotRepeatOperationNotification() = runTest {
        val inbox = CloudNotificationInbox(context)
        assertTrue(inbox.deliverPushImmediately(event.copy(actorDeviceId = CloudDeviceStore(context).deviceId())))
        context.getSharedPreferences("raad_background_work", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).commit()
        assertTrue(inbox.deliverPushImmediately(event.copy(id = "after-logout")))
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test fun androidDisplayedNotificationIsNotPostedAgainByAppCatchUp() = runTest {
        CloudNotificationCenter.ensureChannels(context)
        val native = Notification.Builder(context, CloudNotificationCenter.CHANNEL_ALERT)
            .setSmallIcon(com.radwan.raadpharmacy.R.drawable.ic_notification)
            .setContentTitle("Native Android notification").build()
        manager.notify(CloudNotificationCenter.eventTag(event.id), 0, native)
        val inbox = CloudNotificationInbox(context)
        assertTrue(inbox.deliverPushImmediately(event))
        assertEquals(1, manager.activeNotifications.size)
        assertEquals("Native Android notification", manager.activeNotifications.single().notification
            .extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(inbox.wasDelivered(event.id))
    }

    @Test fun tappingSystemNotificationPersistsAcknowledgementAfterAndroidRemovesIt() = runTest {
        val incoming = Intent().putExtra("native_display", "1").putExtra("event_id", event.id)
            .putExtra("pharmacy_id", "pharmacy").putExtra("event_type", "DEBT_CREATED")
        assertTrue(CloudNotificationInbox(context).markSystemNotificationOpened(incoming))
        assertTrue(CloudNotificationInbox(context).wasDelivered(event.id))
        assertTrue(CloudNotificationInbox(context).deliverPushImmediately(event))
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test fun systemNotificationCannotAcknowledgeAnotherPharmacyOrPrivateRecipient() {
        val inbox = CloudNotificationInbox(context)
        val incoming = Intent().putExtra("native_display", "1").putExtra("event_id", event.id)
            .putExtra("pharmacy_id", "other-pharmacy").putExtra("event_type", "DEBT_CREATED")
        assertFalse(inbox.markSystemNotificationOpened(incoming))
        incoming.putExtra("pharmacy_id", "pharmacy").putExtra("event_type", "TEAM_MESSAGE")
            .putExtra("recipient_user_id", "third-user")
        assertFalse(inbox.markSystemNotificationOpened(incoming))
        assertFalse(inbox.wasDelivered(event.id))
    }
}

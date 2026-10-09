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

    @Test fun serverCustomerSnapshotAndVerifiedBalanceAppearInFullNotification() = runTest {
        val rich = event.copy(id = "rich-snapshot-317", customerName = "محمود العواد",
            amount = 25_000.0, balanceAfter = 75_000.0)
        assertTrue(CloudNotificationInbox(context).deliverPushImmediately(rich))
        val text = manager.activeNotifications.single().notification
            .extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(text.contains("محمود العواد"))
        assertTrue(text.contains("25,000"))
        assertTrue(text.contains("75,000"))
    }

    @Test fun sensitiveCustomerAndAmountStayHiddenWhenPrivacyEnabled() = runTest {
        AppSecurityStore(context).setHideAmounts(true)
        val rich = event.copy(id = "hidden-snapshot-317", customerName = "محمود العواد",
            amount = 25_000.0, balanceAfter = 75_000.0)
        assertTrue(CloudNotificationInbox(context).deliverPushImmediately(rich))
        val text = manager.activeNotifications.single().notification
            .extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertFalse(text.contains("محمود"))
        assertFalse(text.contains("25,000"))
        assertEquals("صيدلية رعد", manager.activeNotifications.single().notification
            .extras.getCharSequence(Notification.EXTRA_TITLE).toString())
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

    @Test fun redactedPayloadCannotRevealLocalCustomerOrFabricateAmount() = runTest {
        assertTrue(CloudNotificationInbox(context).deliverPushImmediately(event.copy(
            privacyRedacted = true, customerName = "محمود العواد", amount = 25_000.0)))
        val text = manager.activeNotifications.single().notification.extras
            .getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertFalse(text.contains("محمود"))
        assertFalse(text.contains("25,000"))
        assertFalse(text.contains("0 د.ع"))
    }

    @Test fun nativeRoutingStartsOnlyAfterSuccessfulCurrentTokenRegistration() {
        val store = CloudDeviceStore(context)
        val row = event.copy(pushServerManaged = true, createdAt = java.time.Instant.now().toString())
        val inbox = CloudNotificationInbox(context)
        assertFalse(inbox.usesManagedPush(row))
        store.saveFcmToken("current-token")
        store.markPushRegistered("current-token", 52)
        assertTrue(inbox.usesManagedPush(row))
        assertFalse(inbox.wasDelivered(row.id))
        assertFalse(inbox.usesManagedPush(row.copy(pushServerManaged = false)))
        store.saveFcmToken("rotated-token")
        assertFalse(inbox.usesManagedPush(row))
        store.markPushRegistered("current-token", 52)
        assertFalse(inbox.usesManagedPush(row))
        store.markPushRegistered("rotated-token", 52)
        assertTrue(inbox.usesManagedPush(row))
        store.clearPushIdentity()
        assertFalse(inbox.usesManagedPush(row))
    }

    @Test fun multipleOperationsKeepDistinctVisibleNotifications() = runTest {
        val inbox = CloudNotificationInbox(context)
        repeat(3) { assertTrue(inbox.deliverPushImmediately(event.copy(id = "offline-$it"))) }
        assertEquals(3, manager.activeNotifications.size)
        repeat(3) { assertTrue(inbox.deliverPushImmediately(event.copy(id = "offline-$it"))) }
        assertEquals(3, manager.activeNotifications.size)
    }

    @Test fun publicLockScreenVersionOmitsDetailsWhilePrivateNotificationContainsThem() = runTest {
        assertTrue(CloudNotificationInbox(context).deliverPushImmediately(event.copy(
            customerName = "محمود العواد", amount = 25_000.0)))
        val notification = manager.activeNotifications.single().notification
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertFalse(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("محمود"))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("25,000"))
    }
}

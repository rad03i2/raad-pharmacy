package com.radwan.raadpharmacy.cloud

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Lifecycle tests use a quiet listener; real phone/FCM delivery requires a device test. */
class QuietListeningService : CloudListeningService() {
    var listenerStarts = 0
    override fun startListening() { listenerStarts++ }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudListeningServiceTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext<Application>()
        listOf("raad_continuous_listening", "raad_background_work").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        context.getSharedPreferences("raad_background_work", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", true).commit()
        CloudContinuousListening.setRunning(false)
    }

    @Test fun startsWithoutAnyActivityAndCreatesASilentOngoingNotification() {
        assertTrue(CloudContinuousListening.isEnabled(context))
        val controller = Robolectric.buildService(QuietListeningService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(CloudContinuousListening.running.value)
        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull(notification)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("دفتر صيدلية رعد", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(CloudListeningService.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CloudListeningService.CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertTrue(notification.actions == null || notification.actions.isEmpty())
        controller.destroy()
        assertFalse(CloudContinuousListening.running.value)
    }

    @Test fun removingRecentTaskDoesNotStopListeningOrStartAnotherListener() {
        val controller = Robolectric.buildService(QuietListeningService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        service.onTaskRemoved(Intent())
        assertTrue(CloudContinuousListening.running.value)
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2))
        assertEquals(1, service.listenerStarts)
        controller.destroy()
    }

    @Test fun explicitStopPersistsAndPreventsRestartOnNextAppOpen() {
        val controller = Robolectric.buildService(QuietListeningService::class.java).create()
        val service = controller.get()
        service.onStartCommand(null, 0, 1)
        assertEquals(Service.START_NOT_STICKY,
            service.onStartCommand(Intent().setAction(CloudListeningService.ACTION_STOP), 0, 2))
        assertFalse(CloudContinuousListening.isEnabled(context))
        assertFalse(CloudContinuousListening.startFromVisibleApp(context))
        controller.destroy()
        val restarted = Robolectric.buildService(QuietListeningService::class.java).create()
        assertEquals(Service.START_NOT_STICKY, restarted.get().onStartCommand(null, 0, 3))
        assertEquals(0, restarted.get().listenerStarts)
        restarted.destroy()
    }

    @Test fun disabledCloudSessionCannotStartTheContinuousService() {
        context.getSharedPreferences("raad_background_work", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", false).commit()
        assertFalse(CloudContinuousListening.startFromVisibleApp(context))
        val controller = Robolectric.buildService(QuietListeningService::class.java).create()
        assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(null, 0, 1))
        assertFalse(CloudContinuousListening.running.value)
        assertEquals(0, controller.get().listenerStarts)
        controller.destroy()
    }

    @Test fun manifestDeclaresTheRealServiceAndRequiredType() {
        val info = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, CloudListeningService::class.java), 0)
        assertFalse(info.exported)
        assertEquals(0, info.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType)
    }
}

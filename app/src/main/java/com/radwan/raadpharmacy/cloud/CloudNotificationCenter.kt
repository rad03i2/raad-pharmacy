package com.radwan.raadpharmacy.cloud

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.radwan.raadpharmacy.MainActivity
import com.radwan.raadpharmacy.R
import com.radwan.raadpharmacy.notifications.PixabaySoundAssets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue

data class CloudExternalNotification(
    val id: String,
    val title: String,
    val body: String,
    val customerId: String? = null
)

object CloudNotificationCenter {
    private const val CHANNEL_ALERT = "raad_cloud_alerts_v4"
    private const val CHANNEL_SILENT = "raad_cloud_silent_v4"
    private const val GROUP_KEY = "raad_cloud_financial_events"
    private const val SUMMARY_ID = 4110
    private val nextId = AtomicInteger(4200)

    fun post(
        context: Context,
        title: String,
        body: String,
        customerId: String? = null,
        audible: Boolean,
        eventId: String? = null
    ) {
        val app = context.applicationContext
        if (!canPost(app)) return
        ensureChannels(app)

        val id = stableId(eventId)
        val notification = builder(
            context = app,
            channelId = if (audible) CHANNEL_ALERT else CHANNEL_SILENT,
            title = title,
            body = body,
            customerId = customerId,
            requestCode = id
        )
            .setGroup(GROUP_KEY)
            .setSilent(true)
            .build()

        notifySafely(app, id, notification)
        if (audible) PixabaySoundAssets.playNotification(app)
    }

    fun postBatch(
        context: Context,
        events: List<CloudExternalNotification>,
        audible: Boolean
    ) {
        if (events.isEmpty()) return
        if (events.size == 1) {
            val event = events.single()
            post(
                context = context,
                title = event.title,
                body = event.body,
                customerId = event.customerId,
                audible = audible,
                eventId = event.id
            )
            return
        }

        val app = context.applicationContext
        if (!canPost(app)) return
        ensureChannels(app)

        events.forEach { event ->
            val id = stableId(event.id)
            val child = builder(
                context = app,
                channelId = CHANNEL_SILENT,
                title = event.title,
                body = event.body,
                customerId = event.customerId,
                requestCode = id
            )
                .setGroup(GROUP_KEY)
                .setSilent(true)
                .build()
            notifySafely(app, id, child)
        }

        val summary = NotificationCompat.Builder(
            app,
            if (audible) CHANNEL_ALERT else CHANNEL_SILENT
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("صيدلية رعد • ${events.size} عمليات جديدة")
            .setContentText("وصلت عمليات تمت أثناء عدم اتصال هذا الهاتف.")
            .setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    events.takeLast(6).forEach { event ->
                        style.addLine(event.title + " — " + event.body)
                    }
                    style.setSummaryText("${events.size} عمليات")
                }
            )
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notifySafely(app, SUMMARY_ID, summary)
        if (audible) PixabaySoundAssets.playNotification(app)
    }

    @SuppressLint("MissingPermission")
    private fun notifySafely(
        context: Context,
        id: Int,
        notification: android.app.Notification
    ) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the explicit check and notify().
        }
    }

    private fun builder(
        context: Context,
        channelId: String,
        title: String,
        body: String,
        customerId: String?,
        requestCode: Int
    ): NotificationCompat.Builder {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (!customerId.isNullOrBlank()) {
                putExtra(MainActivity.EXTRA_CUSTOMER_ID, customerId)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    }

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (manager.getNotificationChannel(CHANNEL_ALERT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERT,
                    "عمليات الأجهزة الأخرى",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "إشعارات الديون والتحصيلات القادمة من الهواتف الأخرى"
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0L, 90L, 55L, 90L)
                    setShowBadge(true)
                }
            )
        }

        if (manager.getNotificationChannel(CHANNEL_SILENT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SILENT,
                    "تنبيهات أثناء استخدام التطبيق",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "إشعار خارجي صامت عندما يكون التطبيق مفتوحًا"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
    }

    private fun canPost(context: Context): Boolean =
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun stableId(eventId: String?): Int =
        eventId?.hashCode()?.absoluteValue?.coerceAtLeast(10_000)
            ?: nextId.incrementAndGet()
}

package com.radwan.raadpharmacy.cloud

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.radwan.raadpharmacy.MainActivity
import com.radwan.raadpharmacy.R
import com.radwan.raadpharmacy.notifications.soundResourceUri
import kotlinx.coroutines.delay

// Versioned because Android does not let an app add sound to an existing silent channel.
object CloudNotificationCenter {
    internal const val CHANNEL_ALERT = "raad_cloud_alerts_v6_audible"
    fun post(
        context: Context,
        title: String,
        body: String,
        customerId: String? = null,
        audible: Boolean = true,
        eventId: String? = null
    ): Boolean {
        val app = context.applicationContext
        if (!canPost(app)) return false
        ensureChannels(app)
        val id = stableId(eventId ?: java.util.UUID.randomUUID().toString())
        val intent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (!customerId.isNullOrBlank()) putExtra(MainActivity.EXTRA_CUSTOMER_ID, customerId)
        }
        val pending = PendingIntent.getActivity(app, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSound(soundResourceUri(app, R.raw.pixabay_notification_037))
            .setSilent(!audible)
            .build()
        return notifySafely(app, id, notification)
    }

    suspend fun postBatch(context: Context, events: List<CloudExternalNotification>) {
        events.forEach { event ->
            post(context, event.title, event.body, event.customerId, true, event.id)
            if (events.size > 1) delay(3_000L)
        }
    }

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ALERT) != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERT,
            "عمليات الأجهزة الأخرى", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "صوت مستقل لكل عملية، داخل التطبيق وخارجه وبعد عودة الإنترنت"
            setSound(soundResourceUri(context, R.raw.pixabay_notification_037), attributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 90L, 55L, 90L)
            setShowBadge(true)
        })
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ALERT)?.importance != NotificationManager.IMPORTANCE_NONE)

    @SuppressLint("MissingPermission")
    private fun notifySafely(context: Context, id: Int, notification: android.app.Notification): Boolean {
        if (!canPost(context)) return false
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (_: SecurityException) { false }
    }

    private fun stableId(eventId: String): Int = (eventId.hashCode() and Int.MAX_VALUE).coerceAtLeast(10_000)
}

data class CloudExternalNotification(
    val id: String,
    val title: String,
    val body: String,
    val customerId: String? = null
)

package com.radwan.raadpharmacy.cloud

import android.Manifest
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
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.radwan.raadpharmacy.MainActivity
import com.radwan.raadpharmacy.R
import java.util.concurrent.atomic.AtomicInteger

class PharmacyMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        CloudDeviceStore(applicationContext).saveFcmToken(token)
        CloudSyncRuntime.requestSync(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "صيدلية رعد"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: "تم تحديث دفتر الديون."
        val customerId = message.data["customer_id"]

        ensureChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (!customerId.isNullOrBlank()) {
                putExtra(MainActivity.EXTRA_CUSTOMER_ID, customerId)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            nextId.incrementAndGet(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val canNotify =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

        if (canNotify) {
            runCatching {
                NotificationManagerCompat.from(this)
                    .notify(nextId.incrementAndGet(), notification)
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "التحديثات السحابية",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "إشعارات الديون والتحصيلات والتحديثات من الأجهزة الأخرى"
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "raad_cloud_updates"
        private val nextId = AtomicInteger(4200)
    }
}

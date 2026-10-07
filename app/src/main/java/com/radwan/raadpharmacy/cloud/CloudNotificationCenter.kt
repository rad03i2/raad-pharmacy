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
import com.radwan.raadpharmacy.MainActivity
import com.radwan.raadpharmacy.R
import java.util.concurrent.atomic.AtomicInteger

object CloudNotificationCenter {
    private const val CHANNEL_ID = "raad_cloud_updates"
    private val nextId = AtomicInteger(4200)

    fun post(
        context: Context,
        title: String,
        body: String,
        customerId: String? = null
    ) {
        val appContext = context.applicationContext
        ensureChannel(appContext)

        val canNotify =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

        if (!canNotify) return

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (!customerId.isNullOrBlank()) {
                putExtra(MainActivity.EXTRA_CUSTOMER_ID, customerId)
            }
        }

        val requestCode = nextId.incrementAndGet()
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        runCatching {
            NotificationManagerCompat.from(appContext)
                .notify(requestCode, notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
}

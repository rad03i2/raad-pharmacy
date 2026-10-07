package com.radwan.raadpharmacy.notifications

import android.Manifest
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
import com.radwan.raadpharmacy.security.AppSecurityStore
import com.radwan.raadpharmacy.util.formatMoney

enum class FinancialOperationKind {
    DEBT,
    PAYMENT,
    FULL_SETTLEMENT
}

data class FinancialFeedbackSettings(
    val operationSound: OperationSoundPreset,
    val notificationSound: NotificationSoundPreset,
    val operationSoundEnabled: Boolean = true
)

class FinancialFeedbackStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun state(): FinancialFeedbackSettings =
        FinancialFeedbackSettings(
            operationSound = OperationSoundPreset.fromStorage(
                prefs.getString(KEY_OPERATION_SOUND, null)
            ),
            notificationSound = NotificationSoundPreset.fromStorage(
                prefs.getString(KEY_NOTIFICATION_SOUND, null)
            ),
            operationSoundEnabled = prefs.getBoolean(KEY_OPERATION_SOUND_ENABLED, true)
        )

    fun setOperationSound(preset: OperationSoundPreset) {
        prefs.edit().putString(KEY_OPERATION_SOUND, preset.storageValue).apply()
    }

    fun setNotificationSound(preset: NotificationSoundPreset) {
        prefs.edit().putString(KEY_NOTIFICATION_SOUND, preset.storageValue).apply()
    }

    fun setOperationSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OPERATION_SOUND_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "gas_ledger_financial_feedback"
        private const val KEY_OPERATION_SOUND = "operation_sound"
        private const val KEY_NOTIFICATION_SOUND = "notification_sound"
        private const val KEY_OPERATION_SOUND_ENABLED = "operation_sound_enabled"
    }
}

data class FinancialOperationReceipt(
    val kind: FinancialOperationKind,
    val customerId: String,
    val customerName: String,
    val amount: Long,
    val balanceAfter: Long
)

object FinancialOperationFeedback {
    private val notificationIds = java.util.concurrent.atomic.AtomicInteger(
        (System.currentTimeMillis() and 0x3FFFFFFF).toInt()
    )

    fun ensureChannel(context: Context): String =
        LedgerNotificationChannels.ensure(context, LedgerNotificationType.OPERATION)

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun postNotification(context: Context, receipt: FinancialOperationReceipt) {
        if (!canPostNotifications(context)) return
        // This is the app/system notification that follows the in-app operation confirmation.
        // It intentionally uses the cloud notification sound, independent from the operation-complete toggle.
        PixabaySoundAssets.playNotification(context)
        val notification = buildNotification(context, receipt)
        try {
            NotificationManagerCompat.from(context).notify(nextNotificationId(), notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and posting.
        }
    }

    internal fun buildNotification(context: Context, receipt: FinancialOperationReceipt): android.app.Notification {
        val channelId = ensureChannel(context)
        val privacy = AppSecurityStore(context).state()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_CUSTOMER_ID, receipt.customerId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            receipt.customerId.hashCode() xor receipt.kind.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (receipt.kind) {
            FinancialOperationKind.DEBT -> "تم تسجيل الدين"
            FinancialOperationKind.PAYMENT -> "تم تسجيل التحصيل"
            FinancialOperationKind.FULL_SETTLEMENT -> "تم تسديد الحساب بالكامل"
        }

        val body = if (privacy.hideAmounts) {
            when (receipt.kind) {
                FinancialOperationKind.DEBT ->
                    "تمت إضافة حركة دين إلى حساب " + receipt.customerName + "."
                FinancialOperationKind.PAYMENT ->
                    "تم تسجيل تحصيل من " + receipt.customerName + "."
                FinancialOperationKind.FULL_SETTLEMENT ->
                    "تم تسديد حساب " + receipt.customerName + " بالكامل."
            }
        } else {
            when (receipt.kind) {
                FinancialOperationKind.DEBT ->
                    receipt.customerName + " • " + formatMoney(receipt.amount) +
                        " • الرصيد " + formatMoney(receipt.balanceAfter)
                FinancialOperationKind.PAYMENT ->
                    receipt.customerName + " • " + formatMoney(receipt.amount) +
                        " • المتبقي " + formatMoney(receipt.balanceAfter)
                FinancialOperationKind.FULL_SETTLEMENT ->
                    receipt.customerName + " • " + formatMoney(receipt.amount) +
                        " • الرصيد الآن 0 د.ع"
            }
        }

        val publicVersion = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("دفتر صيدلية رعد")
            .setContentText("تم حفظ عملية مالية بنجاح.")
            .build()

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_notification, "عرض الحساب", pendingIntent)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()

    }

    fun postPreviewNotification(context: Context) {
        if (!canPostNotifications(context)) return
        val notification = NotificationCompat.Builder(context, ensureChannel(context))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("إشعار تجريبي من دفتر صيدلية رعد")
            .setContentText("هذا هو الصوت المختار لإشعارات الهاتف.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(nextNotificationId(), notification)
        } catch (_: SecurityException) {
            // The system permission may change while the test notification is being built.
        }
    }

    fun playSelectedOperationSound(context: Context) {
        val state = FinancialFeedbackStore(context).state()
        if (!state.operationSoundEnabled) return
        playOperationSound(context, state.operationSound)
    }

    fun playOperationSound(context: Context, preset: OperationSoundPreset) {
        PixabaySoundAssets.playOperation(context)
    }

    fun playNotificationSound(context: Context, preset: NotificationSoundPreset) {
        PixabaySoundAssets.playNotification(context)
    }

    fun stopPreviewSound() { FeedbackSoundPlayer.stop() }

    internal fun nextNotificationId(): Int = notificationIds.getAndUpdate {
        if (it == Int.MAX_VALUE) 30_000 else it + 1
    }
}

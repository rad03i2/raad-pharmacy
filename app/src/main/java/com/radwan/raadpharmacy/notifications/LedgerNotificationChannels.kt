package com.radwan.raadpharmacy.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings

enum class LedgerNotificationType(val prefix: String, val title: String, val legacyId: String) {
    OPERATION("operation", "تأكيد العمليات المالية", "financial_operation_confirmations_v2"),
    REMINDER("reminder", "متابعة الديون", "debt_follow_up")
}

object LedgerNotificationChannels {
    private const val GROUP_ID = "ledger_alerts"

    fun channelId(type: LedgerNotificationType, preset: NotificationSoundPreset): String =
        "ledger_${type.prefix}_heads_up_v3_${preset.storageValue}"

    fun ensure(
        context: Context,
        type: LedgerNotificationType,
        preset: NotificationSoundPreset = FinancialFeedbackStore(context).state().notificationSound
    ): String {
        val id = channelId(type, preset)
        val manager = context.getSystemService(NotificationManager::class.java)
        // Existing user settings belong to the user, including muted or disabled channels.
        if (manager.getNotificationChannel(id) != null) return id
        val previous = manager.getNotificationChannel(type.legacyId)
        val importance = when {
            previous?.importance == NotificationManager.IMPORTANCE_NONE -> NotificationManager.IMPORTANCE_NONE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && previous != null &&
                previous.hasUserSetImportance() && previous.importance < NotificationManager.IMPORTANCE_HIGH -> previous.importance
            else -> NotificationManager.IMPORTANCE_HIGH
        }
        manager.createNotificationChannelGroup(NotificationChannelGroup(GROUP_ID, "إشعارات دفتر صيدلية رعد"))
        manager.createNotificationChannel(NotificationChannel(id, type.title + " • " + preset.title, importance).apply {
            group = GROUP_ID
            description = "تنبيه منبثق بالصوت المختار؛ تتحكم إعدادات الهاتف بالظهور والصوت."
            setSound(soundResourceUri(context, preset.resourceId), AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 90L, 60L, 90L)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
        })
        return id
    }

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, ensure(context, LedgerNotificationType.OPERATION))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

package com.radwan.raadpharmacy.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.radwan.raadpharmacy.MainActivity
import com.radwan.raadpharmacy.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** User-visible continuous pharmacy alert monitoring, separate from ledger sync jobs. */
open class CloudListeningService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listening = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CloudContinuousListening.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!CloudContinuousListening.isEnabled(this) || !CloudSyncScheduler.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Promote before auth initialization, database access or any network operation.
        showForegroundNotification()
        CloudContinuousListening.setRunning(true)
        if (!listening) {
            listening = true
            startListening()
        }
        return START_STICKY
    }

    protected open fun startListening() {
        scope.launch {
            while (CloudContinuousListening.isEnabled(this@CloudListeningService) &&
                CloudSyncScheduler.isEnabled(this@CloudListeningService)) {
                try {
                    CloudSyncRuntime.maintainNotificationListening(applicationContext)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    FirebaseCrashlytics.getInstance().recordException(error)
                }
                delay(30_000L)
            }
            stopSelf()
        }
    }

    private fun showForegroundNotification() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "التشغيل المستمر في الخلفية", NotificationManager.IMPORTANCE_LOW).apply {
                description = "يظهر أثناء متابعة إشعارات الصيدلية والتطبيق مغلق"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, CloudListeningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("صيدلية رعد تعمل في الخلفية")
            .setContentText("متابعة التنبيهات مستمرة. قد يزيد استهلاك البطارية.")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "إيقاف التشغيل المستمر", stop)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        CloudContinuousListening.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        internal const val CHANNEL = "raad_continuous_listening_v1"
        internal const val NOTIFICATION_ID = 9701
        internal const val ACTION_STOP = "com.radwan.raadpharmacy.STOP_CONTINUOUS_LISTENING"
    }
}

object CloudContinuousListening {
    private val active = MutableStateFlow(false)
    val running = active.asStateFlow()

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences("raad_continuous_listening", Context.MODE_PRIVATE)
        .getBoolean("enabled", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences("raad_continuous_listening", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", enabled).commit()
        if (!enabled) stop(context)
    }

    /** Call only while an Activity is visible; never start from boot, timers or WorkManager. */
    fun startFromVisibleApp(context: Context): Boolean {
        if (!isEnabled(context) || !CloudSyncScheduler.isEnabled(context)) return false
        return try {
            ContextCompat.startForegroundService(context.applicationContext,
                Intent(context.applicationContext, CloudListeningService::class.java))
            true
        } catch (error: IllegalStateException) {
            FirebaseCrashlytics.getInstance().recordException(error)
            false
        } catch (error: SecurityException) {
            FirebaseCrashlytics.getInstance().recordException(error)
            false
        }
    }

    fun stop(context: Context) {
        context.applicationContext.stopService(Intent(context.applicationContext, CloudListeningService::class.java))
        setRunning(false)
    }

    internal fun setRunning(value: Boolean) { active.value = value }
}

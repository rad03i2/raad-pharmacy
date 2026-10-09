package com.radwan.raadpharmacy

import android.content.Intent
import android.os.Bundle
import android.os.StrictMode
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import com.radwan.raadpharmacy.data.LedgerReleaseCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.radwan.raadpharmacy.cloud.CloudAuthGate
import com.radwan.raadpharmacy.cloud.CloudNotificationInbox

class MainActivity : FragmentActivity() {
    private val notificationOpenSettings = mutableStateOf(false)
    private val notificationCustomerId = mutableStateOf<String?>(null)
    private val notificationOpenFollowup = mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }
        enableEdgeToEdge()
        readNotificationIntent(intent)
        setContent {
            val resetFinished = remember { mutableStateOf(false) }
            val resetError = remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                try {
                    withContext(Dispatchers.IO) { LedgerReleaseCleanup.clearOnce(applicationContext) }
                    resetFinished.value = true
                } catch (_: Exception) {
                    resetError.value = true
                }
            }
            if (resetFinished.value) {
                CloudAuthGate {
                PharmacyLedgerApp(
                    notificationCustomerId = notificationCustomerId.value,
                    notificationOpenSettings = notificationOpenSettings.value,
                    notificationOpenFollowup = notificationOpenFollowup.value,
                    onNotificationHandled = { notificationCustomerId.value = null; notificationOpenSettings.value = false; notificationOpenFollowup.value = false }
                )
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (resetError.value) Text("تعذر تهيئة دفتر الزبائن. أعد فتح التطبيق.")
                    else CircularProgressIndicator()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readNotificationIntent(intent)
    }

    private fun readNotificationIntent(incoming: Intent?) {
        notificationOpenSettings.value = incoming?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true
        notificationOpenFollowup.value = incoming?.getBooleanExtra(EXTRA_OPEN_FOLLOWUP, false) == true
        notificationCustomerId.value = incoming?.getStringExtra(EXTRA_CUSTOMER_ID)
        if (incoming?.getStringExtra("native_display") == "1" &&
            CloudNotificationInbox(this).markSystemNotificationOpened(incoming)) {
            notificationOpenSettings.value = incoming.getStringExtra("event_type") in setOf("TEAM_ALERT", "TEAM_MESSAGE")
            notificationCustomerId.value = incoming.getStringExtra("customer_id")?.takeIf(String::isNotBlank)
        }
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "notification_open_settings"
        const val EXTRA_OPEN_FOLLOWUP = "notification_open_followup"
        const val EXTRA_CUSTOMER_ID = "notification_customer_id"
    }
}

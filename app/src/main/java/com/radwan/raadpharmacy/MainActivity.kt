package com.radwan.raadpharmacy

import android.content.Intent
import android.os.Bundle
import android.os.StrictMode
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.radwan.raadpharmacy.cloud.CloudAuthGate

class MainActivity : FragmentActivity() {
    private val notificationOpenSettings = mutableStateOf(false)
    private val notificationCustomerId = mutableStateOf<String?>(null)
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
        notificationOpenSettings.value = intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true
        notificationCustomerId.value = intent?.getStringExtra(EXTRA_CUSTOMER_ID)
        setContent {
            CloudAuthGate {
                PharmacyLedgerApp(
                    notificationCustomerId = notificationCustomerId.value,
                    notificationOpenSettings = notificationOpenSettings.value,
                    onNotificationHandled = { notificationCustomerId.value = null; notificationOpenSettings.value = false }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationOpenSettings.value = intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)
        notificationCustomerId.value = intent.getStringExtra(EXTRA_CUSTOMER_ID)
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "notification_open_settings"
        const val EXTRA_CUSTOMER_ID = "notification_customer_id"
    }
}

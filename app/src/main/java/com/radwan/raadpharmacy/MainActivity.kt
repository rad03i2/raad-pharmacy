package com.radwan.raadpharmacy

import android.content.Intent
import android.os.Bundle
import android.os.StrictMode
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : FragmentActivity() {
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
        notificationCustomerId.value = intent?.getStringExtra(EXTRA_CUSTOMER_ID)
        setContent {
            PharmacyLedgerApp(
                notificationCustomerId = notificationCustomerId.value,
                onNotificationHandled = { notificationCustomerId.value = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationCustomerId.value = intent.getStringExtra(EXTRA_CUSTOMER_ID)
    }

    companion object {
        const val EXTRA_CUSTOMER_ID = "notification_customer_id"
    }
}

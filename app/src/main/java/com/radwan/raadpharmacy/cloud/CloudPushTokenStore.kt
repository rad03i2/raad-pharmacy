package com.radwan.raadpharmacy.cloud

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID

class CloudDeviceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "raad_cloud_device",
        Context.MODE_PRIVATE
    )

    fun deviceId(): String =
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    fun pushTokenRowId(): String =
        prefs.getString("push_token_row_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("push_token_row_id", it).apply()
        }

    fun fcmToken(): String? = prefs.getString("fcm_token", null)

    fun saveFcmToken(token: String) {
        prefs.edit().putString("fcm_token", token).apply()
    }

    fun isBootstrapped(pharmacyId: String): Boolean =
        prefs.getBoolean("bootstrapped_$pharmacyId", false)

    fun markBootstrapped(pharmacyId: String) {
        prefs.edit().putBoolean("bootstrapped_$pharmacyId", true).apply()
    }

    fun refreshFcmToken(context: Context) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (token.isNotBlank()) {
                saveFcmToken(token)
                CloudSyncRuntime.requestSync(context)
            }
        }
    }
}

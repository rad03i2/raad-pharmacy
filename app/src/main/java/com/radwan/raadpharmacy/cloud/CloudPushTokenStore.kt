package com.radwan.raadpharmacy.cloud

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID

class CloudDeviceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "raad_cloud_device",
        Context.MODE_PRIVATE
    )

    fun deviceId(): String = synchronized(identityLock) {
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("device_id", it).commit())
        }
    }

    fun pushTokenRowId(): String = synchronized(identityLock) {
        prefs.getString("push_token_row_id", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("push_token_row_id", it).commit())
        }
    }

    fun savePushIdentity(userId: String, pharmacyId: String) {
        synchronized(identityLock) {
            val edit = prefs.edit().putString("push_user_id", userId).putString("push_pharmacy_id", pharmacyId)
            if (pushUserId() != userId || pushPharmacyId() != pharmacyId) edit.remove("push_registered_version")
            edit.commit()
        }
    }

    fun clearPushIdentity() {
        prefs.edit().remove("push_user_id").remove("push_pharmacy_id").remove("push_registered_version").commit()
    }

    fun pushUserId(): String? = prefs.getString("push_user_id", null)
    fun pushPharmacyId(): String? = prefs.getString("push_pharmacy_id", null)

    fun fcmToken(): String? = prefs.getString("fcm_token", null)

    fun saveFcmToken(token: String) {
        synchronized(identityLock) {
            val edit = prefs.edit().putString("fcm_token", token)
            if (prefs.getString("fcm_token", null) != token) edit.remove("push_registered_version")
            edit.commit()
        }
    }

    fun markPushRegistered(token: String, version: Int) = synchronized(identityLock) {
        if (fcmToken() == token) prefs.edit().putInt("push_registered_version", version).commit()
    }

    fun usesManagedPush(): Boolean = prefs.getInt("push_registered_version", 0) >= 52

    companion object { private val identityLock = Any() }

    fun isBootstrapped(pharmacyId: String): Boolean =
        prefs.getBoolean("bootstrapped_$pharmacyId", false)

    fun markBootstrapped(pharmacyId: String) {
        prefs.edit().putBoolean("bootstrapped_$pharmacyId", true).apply()
    }

    fun refreshFcmToken(context: Context) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (token.isNotBlank()) {
                saveFcmToken(token)
                CloudPushRegistrationWorker.enqueue(context)
            }
        }.addOnFailureListener {
            if (CloudSyncScheduler.isEnabled(context)) CloudPushRegistrationWorker.enqueue(context)
        }
    }
}

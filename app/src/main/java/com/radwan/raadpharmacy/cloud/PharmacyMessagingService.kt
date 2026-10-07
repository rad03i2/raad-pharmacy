package com.radwan.raadpharmacy.cloud

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

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

        CloudNotificationCenter.post(
            context = applicationContext,
            title = title,
            body = body,
            customerId = customerId
        )
    }
}

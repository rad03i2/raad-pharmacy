package com.radwan.raadpharmacy.cloud

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.Instant

class PharmacyMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        CloudDeviceStore(applicationContext).saveFcmToken(token)
        CloudSyncRuntime.requestSync(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val eventId = data["event_id"]

        if (!eventId.isNullOrBlank()) {
            val row = CloudNotificationEventRow(
                id = eventId,
                pharmacyId = data["pharmacy_id"].orEmpty(),
                actorUserId = data["actor_user_id"],
                actorDisplayName = data["actor_display_name"],
                actorDeviceId = data["actor_device_id"],
                eventType = data["event_type"].orEmpty(),
                customerId = data["customer_id"],
                transactionId = data["transaction_id"],
                amount = data["amount"]?.toDoubleOrNull() ?: 0.0,
                transactionType = data["transaction_type"],
                createdAt = data["created_at"] ?: Instant.now().toString()
            )

            runBlocking(Dispatchers.IO) {
                runCatching {
                    val eventAge = System.currentTimeMillis() -
                        runCatching { Instant.parse(row.createdAt).toEpochMilli() }
                            .getOrDefault(System.currentTimeMillis())

                    val inbox = CloudNotificationInbox(applicationContext)
                    if (eventAge >= DELAYED_PUSH_BATCH_AFTER_MS) {
                        inbox.catchUp()
                    } else {
                        inbox.deliverPush(row)
                    }
                }
            }
            return
        }

        val title = message.notification?.title
            ?: data["title"]
            ?: "صيدلية رعد"
        val body = message.notification?.body
            ?: data["body"]
            ?: "تم تحديث دفتر الديون."

        CloudNotificationCenter.post(
            context = applicationContext,
            title = title,
            body = body,
            customerId = data["customer_id"],
            audible = !CloudUiEvents.isAppForeground()
        )
    }
}

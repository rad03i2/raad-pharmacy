package com.radwan.raadpharmacy.cloud

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.google.firebase.crashlytics.FirebaseCrashlytics

class PharmacyMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        CloudDeviceStore(applicationContext).saveFcmToken(token)
        if (CloudSyncScheduler.isEnabled(applicationContext)) CloudPushRegistrationWorker.enqueue(applicationContext)
    }

    override fun onDeletedMessages() {
        if (CloudSyncScheduler.isEnabled(applicationContext)) CloudSyncScheduler.enqueue(applicationContext)
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
                recipientUserId = data["recipient_user_id"],
                messageBody = data["message_body"],
                messageReadAt = data["message_read_at"]?.takeIf(String::isNotBlank),
                customerId = data["customer_id"],
                transactionId = data["transaction_id"],
                amount = data["amount"]?.toDoubleOrNull() ?: 0.0,
                customerName = data["customer_name"]?.takeIf(String::isNotBlank),
                balanceAfter = data["balance_after"]?.toDoubleOrNull(),
                transactionType = data["transaction_type"],
                createdAt = data["created_at"] ?: Instant.now().toString()
            )

            // FCM grants only a short callback lifetime. No auth refresh or server fetch here.
            runCatching {
                runBlocking { withTimeout(4_000L) { CloudNotificationInbox(applicationContext).deliverPushImmediately(row) } }
            }.onFailure { FirebaseCrashlytics.getInstance().recordException(it) }
            CloudNotificationDeliveryWorker.enqueue(applicationContext, row)
            CloudSyncScheduler.enqueue(applicationContext)
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
            audible = true
        )
    }

}

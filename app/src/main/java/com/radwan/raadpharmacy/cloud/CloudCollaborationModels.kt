package com.radwan.raadpharmacy.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CloudNotificationEventRow(
    val id: String,
    @SerialName("pharmacy_id") val pharmacyId: String,
    @SerialName("actor_user_id") val actorUserId: String? = null,
    @SerialName("actor_display_name") val actorDisplayName: String? = null,
    @SerialName("actor_device_id") val actorDeviceId: String? = null,
    @SerialName("event_type") val eventType: String,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("transaction_id") val transactionId: String? = null,
    val amount: Double = 0.0,
    @SerialName("transaction_type") val transactionType: String? = null,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class CloudPresenceRow(
    @SerialName("user_id") val userId: String,
    @SerialName("pharmacy_id") val pharmacyId: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("is_online") val isOnline: Boolean,
    @SerialName("last_seen_at") val lastSeenAt: String
)

@Serializable
data class CloudTeamProfileRow(
    val id: String,
    @SerialName("pharmacy_id") val pharmacyId: String,
    @SerialName("display_name") val displayName: String,
    val role: String,
    @SerialName("is_hidden") val isHidden: Boolean = false,
    @SerialName("avatar_path") val avatarPath: String? = null
)

package com.radwan.raadpharmacy.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CloudProfileRow(
    val id: String,
    @SerialName("pharmacy_id") val pharmacyId: String,
    @SerialName("display_name") val displayName: String,
    val role: String,
    @SerialName("is_hidden") val isHidden: Boolean = false,
    @SerialName("avatar_path") val avatarPath: String? = null
)

@Serializable
data class CloudCustomerRow(
    val id: String,
    @SerialName("pharmacy_id") val pharmacyId: String,
    val name: String,
    val phone: String? = null,
    val area: String = "",
    val address: String? = null,
    @SerialName("opening_debt") val openingDebt: Long = 0L,
    val notes: String? = null,
    @SerialName("photo_path") val photoPath: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null
)

@Serializable
data class CloudCustomerWrite(
    val id: String,
    @SerialName("pharmacy_id") val pharmacyId: String,
    val name: String,
    val phone: String? = null,
    val area: String = "",
    val address: String? = null,
    @SerialName("opening_debt") val openingDebt: Long,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null
)

@Serializable
data class CloudTransactionRow(
    val id: String,
    @SerialName("pharmacy_id") val pharmacyId: String,
    @SerialName("customer_id") val customerId: String,
    @SerialName("operation_id") val operationId: String,
    val type: String,
    val amount: Double,
    val notes: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("updated_by") val updatedBy: String? = null,
    @SerialName("deleted_by") val deletedBy: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null
)

@Serializable
data class CloudTransactionWrite(
    val id: String,
    @SerialName("pharmacy_id") val pharmacyId: String,
    @SerialName("customer_id") val customerId: String,
    @SerialName("operation_id") val operationId: String,
    val type: String,
    val amount: Long,
    val notes: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null
)

@Serializable
data class CloudDeviceWrite(
    val id: String,
    @SerialName("pharmacy_id") val pharmacyId: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val platform: String = "ANDROID",
    @SerialName("last_seen_at") val lastSeenAt: String
)

@Serializable
data class CloudPushTokenWrite(
    val id: String,
    @SerialName("pharmacy_id") val pharmacyId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    val token: String,
    val provider: String = "FCM",
    @SerialName("app_version_code") val appVersionCode: Int = 0,
    @SerialName("hide_notification_details") val hideNotificationDetails: Boolean = true,
    @SerialName("deleted_at") val deletedAt: String? = null
)

@Serializable
data class DeletedAtPatch(
    @SerialName("deleted_at") val deletedAt: String
)

@Serializable
data class DeletedAtDevicePatch(
    @SerialName("deleted_at") val deletedAt: String,
    @SerialName("device_id") val deviceId: String
)

package com.radwan.raadpharmacy.cloud

import android.content.Context

/** Author confirmation: FCM intentionally excludes the originating device. */
internal object CloudUploadFeedback {
    fun post(context: Context, row: CloudOutboxEntity): Boolean = CloudNotificationCenter.post(
        context, "تمت مزامنة العملية", "حُفظت العملية في السحابة وأُضيفت إشعاراتها للفريق.",
        eventId = eventId(row), audible = false
    )
    fun eventId(row: CloudOutboxEntity) = "raad-upload:${row.revision}"
}

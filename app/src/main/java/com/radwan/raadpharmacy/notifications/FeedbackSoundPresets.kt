package com.radwan.raadpharmacy.notifications

import android.content.Context
import android.net.Uri
import com.radwan.raadpharmacy.R

enum class OperationSoundPreset(
    val storageValue: String,
    val title: String,
    val description: String,
    val resourceId: Int
) {
    PIXABAY_OPERATION(
        "pixabay_som_matricula",
        "صوت اكتمال العملية",
        "Som Matricula من Pixabay — صوت قصير وواضح عند نجاح العملية.",
        R.raw.cash_register
    );

    companion object {
        fun fromStorage(value: String?): OperationSoundPreset = PIXABAY_OPERATION
    }
}

enum class NotificationSoundPreset(
    val storageValue: String,
    val title: String,
    val description: String,
    val resourceId: Int
) {
    PIXABAY_NOTIFICATION(
        "pixabay_notification_037",
        "صوت التنبيه السحابي",
        "New Notification 037 من Pixabay — للتنبيه الداخلي والخارجي.",
        R.raw.cash_ping
    );

    companion object {
        fun fromStorage(value: String?): NotificationSoundPreset = PIXABAY_NOTIFICATION
    }
}

internal fun soundResourceUri(context: Context, resourceId: Int): Uri =
    Uri.parse(
        "android.resource://${context.packageName}/raw/" +
            context.resources.getResourceEntryName(resourceId)
    )

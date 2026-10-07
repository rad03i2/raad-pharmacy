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
    CASH_REGISTER("cash_register", "آلة المحاسبة", "رنين نقدي واضح عند اكتمال العملية.", R.raw.cash_register),
    COIN_CASCADE("coin_cascade", "رنين العملات", "نغمات معدنية سريعة بطابع مالي.", R.raw.coin_cascade),
    POS_PREMIUM("pos_premium", "تأكيد الدفع", "نغمة دفع إلكتروني ناعمة.", R.raw.pos_premium),
    TRI_TONE("classic_tri_tone", "ثلاثي النغمات — Tri-tone", "ثلاث نغمات واضحة مستوحاة من تنبيه الآيفون الكلاسيكي.", R.raw.classic_tri_tone),
    NOTE("classic_note", "نوتة قصيرة — Note", "نغمة واحدة لطيفة مستوحاة من أسلوب الآيفون.", R.raw.classic_note),
    GLASS("classic_glass", "زجاج — Glass", "رنين زجاجي خفيف مستوحى من تنبيهات الآيفون.", R.raw.classic_glass),
    CHIME("classic_chime", "جرس — Chime", "جرس موسيقي هادئ بأسلوب التنبيهات الكلاسيكية.", R.raw.classic_chime),
    COMPLETE("classic_complete", "اكتمال — Complete", "نغمات صاعدة مناسبة لتأكيد التسديد والنجاح.", R.raw.classic_complete),
    REBOUND("classic_rebound", "ارتداد — Rebound", "نغمات قصيرة متتابعة مستوحاة من تنبيهات الآيفون الحديثة.", R.raw.classic_rebound);

    companion object {
        fun fromStorage(value: String?): OperationSoundPreset =
            entries.firstOrNull { it.storageValue == value } ?: CASH_REGISTER
    }
}

enum class NotificationSoundPreset(
    val storageValue: String,
    val title: String,
    val description: String,
    val resourceId: Int
) {
    CASH_PING("cash_ping", "تنبيه نقدي", "رنين مالي قصير وواضح.", R.raw.cash_ping),
    SOFT_BELL("soft_bell", "جرس ناعم", "جرس خفيف دون حدة مزعجة.", R.raw.soft_bell),
    DOUBLE_CHIME("double_chime", "رنين متتابع", "نغمات متتابعة بطابع تطبيقات الدفع.", R.raw.double_chime),
    TRI_TONE("classic_tri_tone", "ثلاثي النغمات — Tri-tone", "ثلاث نغمات واضحة مستوحاة من تنبيه الآيفون الكلاسيكي.", R.raw.classic_tri_tone),
    NOTE("classic_note", "نوتة قصيرة — Note", "نغمة واحدة لطيفة مستوحاة من أسلوب الآيفون.", R.raw.classic_note),
    GLASS("classic_glass", "زجاج — Glass", "رنين زجاجي خفيف مستوحى من تنبيهات الآيفون.", R.raw.classic_glass),
    CHIME("classic_chime", "جرس — Chime", "جرس موسيقي هادئ بأسلوب التنبيهات الكلاسيكية.", R.raw.classic_chime),
    COMPLETE("classic_complete", "اكتمال — Complete", "نغمات صاعدة مناسبة لتأكيد التسديد والنجاح.", R.raw.classic_complete),
    REBOUND("classic_rebound", "ارتداد — Rebound", "نغمات قصيرة متتابعة مستوحاة من تنبيهات الآيفون الحديثة.", R.raw.classic_rebound);

    companion object {
        fun fromStorage(value: String?): NotificationSoundPreset =
            entries.firstOrNull { it.storageValue == value } ?: CASH_PING
    }
}

internal fun soundResourceUri(context: Context, resourceId: Int): Uri =
    Uri.parse("android.resource://${context.packageName}/raw/${context.resources.getResourceEntryName(resourceId)}")

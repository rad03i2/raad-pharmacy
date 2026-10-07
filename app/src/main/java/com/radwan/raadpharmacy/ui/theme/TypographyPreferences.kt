package com.radwan.raadpharmacy.ui.theme

import android.content.Context
import kotlin.math.roundToInt

enum class AppFontPreset(
    val storageKey: String,
    val displayName: String,
    val description: String
) {
    CAIRO(
        storageKey = "cairo",
        displayName = "Cairo",
        description = "واضح وحديث ومناسب للعناوين والواجهات."
    ),
    TAJAWAL(
        storageKey = "tajawal",
        displayName = "Tajawal",
        description = "خفيف ومريح للقراءة اليومية على الهاتف."
    ),
    NOTO_KUFI_ARABIC(
        storageKey = "noto_kufi_arabic",
        displayName = "Noto Kufi Arabic",
        description = "كوفي منظم وواضح بطابع رسمي."
    ),
    NOTO_SANS_ARABIC(
        storageKey = "noto_sans_arabic",
        displayName = "Noto Sans Arabic",
        description = "متوازن جدًا للنصوص الصغيرة والكثيفة."
    );

    companion object {
        fun fromStorage(value: String?): AppFontPreset =
            entries.firstOrNull { it.storageKey == value } ?: CAIRO
    }
}

data class TypographySettings(
    val font: AppFontPreset = AppFontPreset.CAIRO,
    val scale: Float = DEFAULT_TEXT_SCALE
) {
    companion object {
        const val MIN_TEXT_SCALE = 0.80f
        const val MAX_TEXT_SCALE = 1.20f
        const val DEFAULT_TEXT_SCALE = 0.90f
    }
}

class TypographyPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun state(): TypographySettings =
        TypographySettings(
            font = AppFontPreset.fromStorage(
                prefs.getString(KEY_FONT, AppFontPreset.CAIRO.storageKey)
            ),
            scale = prefs
                .getFloat(KEY_SCALE, TypographySettings.DEFAULT_TEXT_SCALE)
                .coerceIn(
                    TypographySettings.MIN_TEXT_SCALE,
                    TypographySettings.MAX_TEXT_SCALE
                )
        )

    fun setFont(font: AppFontPreset): TypographySettings {
        prefs.edit().putString(KEY_FONT, font.storageKey).apply()
        return state()
    }

    fun setScale(scale: Float): TypographySettings {
        val normalized = ((scale * 20f).roundToInt() / 20f)
            .coerceIn(
                TypographySettings.MIN_TEXT_SCALE,
                TypographySettings.MAX_TEXT_SCALE
            )
        if (prefs.getFloat(KEY_SCALE, TypographySettings.DEFAULT_TEXT_SCALE) != normalized) {
            prefs.edit().putFloat(KEY_SCALE, normalized).apply()
        }
        return state()
    }

    fun reset(): TypographySettings {
        prefs.edit()
            .putString(KEY_FONT, AppFontPreset.CAIRO.storageKey)
            .putFloat(KEY_SCALE, TypographySettings.DEFAULT_TEXT_SCALE)
            .apply()
        return state()
    }

    companion object {
        private const val PREFS_NAME = "typography_preferences_v1"
        private const val KEY_FONT = "font"
        private const val KEY_SCALE = "scale"
    }
}

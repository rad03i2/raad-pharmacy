package com.radwan.raadpharmacy.data

import android.content.Context

/** Keeps the latest authenticated profile name available to offline customer creation. */
object CustomerCreatorNameStore {
    private const val PREFS = "raad_customer_creator"
    private const val KEY_DISPLAY_NAME = "display_name"

    fun get(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DISPLAY_NAME, "").orEmpty()

    fun save(context: Context, displayName: String) {
        val normalized = displayName.trim()
        if (normalized.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DISPLAY_NAME, normalized).apply()
    }
}

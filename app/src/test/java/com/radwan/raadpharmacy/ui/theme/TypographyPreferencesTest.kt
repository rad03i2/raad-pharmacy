package com.radwan.raadpharmacy.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TypographyPreferencesTest {
    private lateinit var context: Context
    private lateinit var store: TypographyPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(
            "typography_preferences_v1",
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        store = TypographyPreferences(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(
            "typography_preferences_v1",
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }

    @Test
    fun defaults_useCairoAtNinetyPercent() {
        val state = store.state()

        assertEquals(AppFontPreset.CAIRO, state.font)
        assertEquals(0.90f, state.scale, 0.0001f)
    }

    @Test
    fun selectedFont_persists() {
        store.setFont(AppFontPreset.TAJAWAL)

        assertEquals(AppFontPreset.TAJAWAL, store.state().font)
    }

    @Test
    fun scale_snapsToNearestFivePercent() {
        store.setScale(0.93f)
        assertEquals(0.95f, store.state().scale, 0.0001f)

        store.setScale(1.07f)
        assertEquals(1.05f, store.state().scale, 0.0001f)
    }

    @Test
    fun scale_isClampedToSafeRange() {
        store.setScale(0.30f)
        assertEquals(0.80f, store.state().scale, 0.0001f)

        store.setScale(2.0f)
        assertEquals(1.20f, store.state().scale, 0.0001f)
    }

    @Test
    fun reset_restoresCairoAndDefaultSize() {
        store.setFont(AppFontPreset.NOTO_KUFI_ARABIC)
        store.setScale(1.20f)

        val state = store.reset()

        assertEquals(AppFontPreset.CAIRO, state.font)
        assertEquals(0.90f, state.scale, 0.0001f)
    }
}

package com.radwan.raadpharmacy.update

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.BuildConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** No network traffic or package installation is performed in unit tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateManagerTest {
    private lateinit var app: Context
    private val hash = "f".repeat(64)

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext<Application>()
        AppUpdateManager.reset(app)
    }

    @Test fun ignoresCurrentOrOlderDownloadSession() {
        val preferences = app.getSharedPreferences("raad_app_update_v1", Context.MODE_PRIVATE)
        preferences.edit().putLong("id", 200L).putString("tag", "v3.3.13")
            .putLong("code", BuildConfig.VERSION_CODE.toLong()).putString("sha", hash).commit()
        assertNull(AppUpdateManager.pending(app))
    }

    @Test fun onlyResumesWellFormedNewerStableSession() {
        val preferences = app.getSharedPreferences("raad_app_update_v1", Context.MODE_PRIVATE)
        preferences.edit().putLong("id", 201L).putString("tag", "v3.3.15")
            .putLong("code", BuildConfig.VERSION_CODE.toLong() + 1).putString("sha", hash).commit()
        val session = AppUpdateManager.pending(app)
        assertNotNull(session)
        assertEquals("v3.3.15", session!!.tag)
        assertEquals(201L, session.id)
        preferences.edit().putString("tag", "../../malicious").commit()
        assertNull(AppUpdateManager.pending(app))
    }

    @Test fun recognizesFourComponentUpdateTags() {
        val preferences = app.getSharedPreferences("raad_app_update_v1", Context.MODE_PRIVATE)
        preferences.edit().putLong("id", 202L).putString("tag", "v3.3.14.1")
            .putLong("code", BuildConfig.VERSION_CODE.toLong() + 1).putString("sha", hash).commit()
        assertEquals("v3.3.14.1", AppUpdateManager.pending(app)?.tag)
    }

    @Test fun refusesTamperedChecksumBeforeAnyInstallation() {
        val preferences = app.getSharedPreferences("raad_app_update_v1", Context.MODE_PRIVATE)
        preferences.edit().putLong("id", 201L).putString("tag", "v3.3.15")
            .putLong("code", BuildConfig.VERSION_CODE.toLong() + 1)
            .putString("sha", "not-a-sha256").commit()
        assertNull(AppUpdateManager.pending(app))
    }
}

package com.radwan.raadpharmacy.update

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.BuildConfig
import org.junit.Assert.*
import org.json.JSONObject
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
    @Test fun parsesOfficialReleasePageWithoutGitHubApi() {
        val tag = "v3.3.19.8"
        val signer = "a".repeat(64)
        val apkName = "Raad-Pharmacy-$tag.apk"
        val hashName = "$apkName.sha256"
        val apkUrl = "https://github.com/rad03i2/raad-pharmacy/releases/download/$tag/$apkName"
        val hashUrl = "https://github.com/rad03i2/raad-pharmacy/releases/download/$tag/$hashName"
        val page = """
            <a href="https://github.com/rad03i2/raad-pharmacy/releases/tag/$tag#start-of-content"></a>
            <div data-test-selector="body-content">
                <p>VersionCode: 63<br>SigningCertificateSHA256: $signer</p>
                <p>إصلاحات وتحسينات</p>
            </div>
        """.trimIndent()
        val assets = """
            <li><a href="$apkUrl">$apkName</a><span>5.2 MB</span></li>
            <li><a href="$hashUrl">$hashName</a><span>94 Bytes</span></li>
        """.trimIndent()

        val manifest = JSONObject(AppUpdateManager.parseLatestReleasePage(page, assets))
        assertEquals(tag, manifest.getString("tag_name"))
        assertEquals(63L, manifest.getString("body").let {
            Regex("(?m)^VersionCode: ([0-9]+)$").find(it)!!.groupValues[1].toLong()
        })
        assertTrue(manifest.getString("body").contains(signer))
        val publishedAssets = manifest.getJSONArray("assets")
        assertEquals(apkName, publishedAssets.getJSONObject(0).getString("name"))
        assertEquals(apkUrl, publishedAssets.getJSONObject(0).getString("browser_download_url"))
        assertTrue(publishedAssets.getJSONObject(0).getLong("size") in 1024L..(150L * 1024L * 1024L))
        assertEquals(hashUrl, publishedAssets.getJSONObject(1).getString("browser_download_url"))
    }

    @Test fun rejectsReleasePageMissingOfficialSignedAssets() {
        val tag = "v3.3.19.8"
        val signer = "b".repeat(64)
        val page = """
            <a href="https://github.com/rad03i2/raad-pharmacy/releases/tag/$tag#start-of-content"></a>
            <div data-test-selector="body-content">
                <p>VersionCode: 63<br>SigningCertificateSHA256: $signer</p>
            </div>
        """.trimIndent()
        try {
            AppUpdateManager.parseLatestReleasePage(page, "<li>No signed APK assets</li>")
            fail("A release without the expected APK and SHA-256 asset must be rejected.")
        } catch (_: IllegalStateException) {
            // Expected: no unverified asset may be synthesized.
        }
    }

}

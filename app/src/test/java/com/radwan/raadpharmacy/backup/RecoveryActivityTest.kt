package com.radwan.raadpharmacy.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryActivityTest {
    @Test fun documentResultAfterActivityRecreationReturnsToComparisonInsteadOfHome() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        RecoverySession.stopForTesting()
        File(app.filesDir, "restore21").deleteRecursively()
        app.getSharedPreferences("raad_restore21_ui", Context.MODE_PRIVATE).edit().clear().commit()
        val input = File(app.cacheDir, "activity-fixture.json").apply {
            writeText(BackupJson.encode(listOf(Customer("00000000-0000-4000-8000-000000000001", "اختبار", createdAt = 1000)), emptyList(), "legacy"))
        }
        val controller = Robolectric.buildActivity(BackupRecoveryActivity::class.java).setup()
        try {
            controller.get().chooseFile()
            val request = checkNotNull(shadowOf(controller.get()).nextStartedActivityForResult)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
            // Lose both the Activity and the in-memory recovery controller while DocumentsUI owns the result.
            RecoverySession.stopForTesting()
            controller.recreate()
            val recreated = controller.get()
            assertTrue(recreated.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(input))))
            val state = withTimeout(10000) { RecoverySession.get(app).state.first { !it.busy && (it.plan != null || it.message != null) } }
            assertNotNull(state.message, state.plan)
            assertFalse(recreated.isFinishing)
            assertEquals(BackupRecoveryActivity::class.java, recreated.javaClass)
        } finally {
            controller.pause().stop().destroy()
            RecoverySession.stopForTesting()
        }
    }
}

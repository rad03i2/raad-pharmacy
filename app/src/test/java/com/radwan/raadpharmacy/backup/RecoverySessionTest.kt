package com.radwan.raadpharmacy.backup

import android.content.Context
import android.net.Uri
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoverySessionTest {
    private lateinit var app: Context
    private lateinit var db: PharmacyLedgerDatabase
    private val sessions = mutableListOf<RecoverySession>()
    private val customer = Customer("00000000-0000-4000-8000-000000000001", "اختبار", createdAt = 1000)
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "restore21").deleteRecursively()
        app.getSharedPreferences("raad_restore21_ui", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder<PharmacyLedgerDatabase>(app).setDriver(AndroidSQLiteDriver()).build()
    }
    @After fun close() { sessions.forEach { it.closeForTesting() }; db.close() }
    private fun session() = RecoverySession.forTesting(app, db.dao()).also { sessions += it }
    private fun source(bytes: ByteArray, name: String = "fixture.backup"): Uri =
        Uri.fromFile(File(app.cacheDir, name).apply { writeBytes(bytes) })
    private suspend fun finished(s: RecoverySession) = withTimeout(10000) {
        s.state.first { !it.busy && (it.plan != null || it.message != null) }
    }
    @Test fun selectedLegacyFileSurvivesProcessLossAndLossOfItsOriginalProvider() = runBlocking {
        val file = source(BackupJson.encode(listOf(customer), emptyList(), "old").toByteArray())
        val first = session(); first.accept(file)
        assertNotNull(finished(first).plan)
        first.closeForTesting(); File(checkNotNull(file.path)).delete()
        val restarted = session()
        assertEquals(1, finished(restarted).plan!!.incoming.customers)
        assertEquals(0, db.dao().customerCount()) // preview and process restart do not import anything
    }
    @Test fun invalidTruncatedAndFutureFormatFilesRemainErrorsWithoutChangingLedger() = runBlocking {
        val good = BackupJson.encode(listOf(customer), emptyList(), "old")
        for ((i, text) in listOf("", "{broken", good.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")).withIndex()) {
            val s = session(); s.accept(source(text.toByteArray(), "invalid-" + i))
            val result = finished(s)
            assertNull(result.plan); assertNotNull(result.message); s.closeForTesting()
        }
        assertEquals(0, db.dao().customerCount())
    }
    @Test fun portableChecksumAndLargeLegacyFilesAreValidatedBeforePreview() = runBlocking {
        val capture = BackupCapture(listOf(customer.toEntity()), emptyList(), emptyList(), 0, false)
        val portable = PortableBackup.encode(capture, JSONObject(), 1000)
        val corrupt = JSONObject(String(portable)).put("sha256", "wrong").toString().toByteArray()
        val broken = session(); broken.accept(source(corrupt)); assertNull(finished(broken).plan); broken.closeForTesting()
        val big = BackupJson.encode(listOf(customer.copy(notes = "x".repeat(2 * 1024 * 1024))), emptyList(), "legacy")
        val large = session(); large.accept(source(big.toByteArray(), "large.json"))
        assertEquals(1, finished(large).plan!!.incoming.customers)
    }
    @Test fun failedNewSelectionNeverFallsBackToThePreviousBackup() = runBlocking {
        val first = session(); first.accept(source(BackupJson.encode(listOf(customer), emptyList(), "old").toByteArray()))
        assertNotNull(finished(first).plan); first.closeForTesting()
        val next = session(); finished(next)
        next.accept(Uri.parse("content://unavailable.fixture/missing"))
        assertNull(finished(next).plan)
        next.closeForTesting()
        val restart = session()
        assertNull(finished(restart).plan)
        assertEquals(0, db.dao().customerCount())
    }
}

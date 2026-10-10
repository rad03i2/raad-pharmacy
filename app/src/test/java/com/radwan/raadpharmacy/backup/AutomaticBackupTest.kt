package com.radwan.raadpharmacy.backup

import android.content.Context
import android.net.Uri
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutomaticBackupTest {
    private lateinit var app: Context
    private lateinit var db: PharmacyLedgerDatabase
    private lateinit var dao: PharmacyLedgerDao
    private val customer = CustomerEntity("c1", "أحمد", null, "", "", 0, "", 1000)
    private class Target(val place: String) : PortableBackupTarget {
        val files = linkedMapOf<String, ByteArray>()
        var failure = false
        var unavailable = false
        override fun write(name: String, bytes: ByteArray): String {
            if (failure) error("لا توجد مساحة")
            PortableBackup.decode(bytes); files[name] = bytes.copyOf()
            list().drop(3).forEach { files.remove(it.name) }
            return name
        }
        override fun list(): List<PortableBackupItem> {
            if (unavailable) error("البطاقة غير متاحة")
            return files.map { PhoneBackupTarget.item(it.key, it.key, it.value.size.toLong(), place) }.sortedByDescending { it.createdAt }
        }
        override fun read(locator: String) = checkNotNull(files[locator])
    }
    @Before fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("raad_automatic_backup_v3", Context.MODE_PRIVATE).edit().clear().commit()
        File(app.noBackupFilesDir, "raad-portable-latest.backup").delete()
        db = Room.inMemoryDatabaseBuilder<PharmacyLedgerDatabase>(app).setDriver(AndroidSQLiteDriver()).build()
        dao = db.dao()
    }
    @After fun close() { db.close() }
    @Test fun firstRunCreatesCompleteBackupWithoutKeyConfirmation() = runTest {
        val phone = Target("phone")
        val engine = AutomaticBackupEngine.forTesting(app, dao, phone)
        assertTrue(engine.process())
        val restored = PortableBackup.decode(phone.files.values.single())
        assertEquals(0L, restored.sequence); assertTrue(restored.ledger.customers.isEmpty())
        assertTrue(engine.state.value.phone.updatedAt > 0)
        assertNull(engine.state.value.phone.error)
    }
    @Test fun committedChangesTriggerCopiesWithoutPressingBackupOrCallingProcess() = runBlocking {
        val phone = Target("phone"); val engine = AutomaticBackupEngine.forTesting(app, dao, phone)
        try {
            engine.start()
            withTimeout(6000) { engine.state.first { it.phone.updatedAt > 0 && !it.busy } }
            dao.insertCustomer(customer)
            withTimeout(6000) { engine.state.first { it.phone.sequence == 1L && !it.busy } }
            dao.insertEntry(LedgerEntryEntity("d1", "c1", "DEBT", 5000, null, null, "", 2000))
            withTimeout(6000) { engine.state.first { it.phone.sequence == 2L && !it.busy } }
            assertEquals(5000L, engine.preview(phone.list().first()).ledger.entries.single().amount)
        } finally { engine.stopForTesting() }
    }
    @Test fun unchangedLedgerDetectsRemovedCardAndRecreatesDeletedPhoneFile() = runTest {
        val phone = Target("phone"); val card = Target("sd")
        val engine = AutomaticBackupEngine.forTesting(app, dao, phone, card)
        engine.setSdEnabled(true)
        card.unavailable = true; assertFalse(engine.process())
        assertNotNull(engine.state.value.sd.error); assertNull(engine.state.value.phone.error)
        phone.files.clear(); card.unavailable = false
        assertTrue(engine.process()); assertEquals(1, phone.files.size); assertNull(engine.state.value.sd.error)
    }
    @Test fun everyCustomerDebtPaymentEditDeleteAndPhotoProducesFullPortableRecoveryPoint() = runTest {
        val phone = Target("phone"); val sd = Target("sd")
        val engine = AutomaticBackupEngine.forTesting(app, dao, phone, sd)
        engine.setSdEnabled(true)
        suspend fun verify() {
            assertTrue(engine.process())
            val first = phone.list().first(); val second = sd.list().first()
            assertArrayEquals(phone.read(first.locator), sd.read(second.locator))
            val copy = engine.preview(first)
            assertEquals(dao.latestBackupSequence(), copy.sequence)
            assertEquals(dao.getCustomers().map { it.id }.toSet(), copy.ledger.customers.map { it.id }.toSet())
            assertEquals(dao.getEntries().map { it.toModel() }.toSet(), copy.ledger.entries.toSet())
            assertEquals(copy.sequence, engine.state.value.phone.sequence)
        }
        dao.insertCustomer(customer); verify()
        val debt = LedgerEntryEntity("d1", "c1", "DEBT", 5000, null, null, "أدوية", 2000)
        dao.insertEntry(debt); verify()
        dao.insertEntry(debt.copy(id = "p1", type = "PAYMENT", amount = 2000)); verify()
        dao.updateEntry(debt.copy(amount = 9000)); verify()
        dao.setBackupPhoto("c1", byteArrayOf(1, 2, 3)); verify()
        assertArrayEquals(byteArrayOf(1, 2, 3), engine.preview(phone.list().first()).photos.single().bytes)
        dao.deleteEntryById("p1"); verify()
        dao.deleteEntryById("d1"); verify()
        dao.updateCustomer(customer.copy(name = "اسم معدّل"), "REMOTE"); verify()
        assertEquals(3, phone.files.size); assertEquals(3, sd.files.size)
    }
    @Test fun missingCardDoesNotStopPhoneAndReconnectionCatchesLatestData() = runTest {
        val phone = Target("phone"); val card = Target("sd")
        val engine = AutomaticBackupEngine.forTesting(app, dao, phone, card)
        engine.setSdEnabled(true)
        val previous = engine.state.value.sd.sequence
        card.failure = true
        dao.insertCustomer(customer)
        assertFalse(engine.process())
        assertEquals(dao.latestBackupSequence(), engine.state.value.phone.sequence)
        assertEquals(previous, engine.state.value.sd.sequence)
        assertNotNull(engine.state.value.sd.error)
        card.failure = false
        assertTrue(engine.process())
        assertEquals(engine.state.value.phone.sequence, engine.state.value.sd.sequence)
        assertNull(engine.state.value.sd.error)
    }
    @Test fun failedPhoneWriteNeverAcknowledgesOrDeletesPrecedingRecoveryPoint() = runTest {
        val phone = Target("phone"); val engine = AutomaticBackupEngine.forTesting(app, dao, phone)
        assertTrue(engine.process())
        val good = phone.files.toMap(); val previous = engine.state.value.phone.sequence
        phone.failure = true; dao.insertCustomer(customer)
        assertFalse(engine.process())
        assertEquals(previous, engine.state.value.phone.sequence)
        assertEquals(good.keys, phone.files.keys)
        phone.failure = false; assertTrue(engine.process())
        assertEquals(1, engine.preview(phone.list().first()).ledger.customers.size)
    }
    @Test fun restartUsesDurableWatermarkEvenAfterOldJournalWasPruned() = runTest {
        val phone = Target("phone")
        val first = AutomaticBackupEngine.forTesting(app, dao, phone)
        first.process(); dao.insertCustomer(customer); dao.pruneBackupChanges(dao.latestBackupSequence())
        val restarted = AutomaticBackupEngine.forTesting(app, dao, phone)
        assertTrue(restarted.process()); assertEquals(1L, restarted.state.value.phone.sequence)
        assertEquals("c1", restarted.preview(phone.list().first()).ledger.customers.single().id)
    }
    @Test fun disablingCardLeavesItsFilesAndPhoneContinuesUpdating() = runTest {
        val phone = Target("phone"); val card = Target("sd")
        val engine = AutomaticBackupEngine.forTesting(app, dao, phone, card)
        engine.setSdEnabled(true); val retained = card.files.keys.toSet()
        engine.setSdEnabled(false); dao.insertCustomer(customer); assertTrue(engine.process())
        assertEquals(retained, card.files.keys); assertEquals(1L, engine.state.value.phone.sequence)
    }
    @Test fun corruptionIsRejectedWithoutAnInstallationKey() = runTest {
        dao.insertCustomer(customer)
        val bytes = PortableBackup.encode(dao.captureBackup(), JSONObject(), 5000)
        assertEquals("c1", PortableBackup.decode(bytes).ledger.customers.single().id)
        val bad = JSONObject(String(bytes)).put("sha256", "0".repeat(64)).toString().toByteArray()
        assertThrows(IllegalArgumentException::class.java) { PortableBackup.decode(bad) }
        assertThrows(Exception::class.java) { PortableBackup.decode(bytes.copyOf(bytes.size / 2)) }
    }
    @Test fun portableFileRestoresOnADifferentInstallationKeyWithExistingSafetyHold() = runTest {
        dao.insertCustomer(customer)
        dao.insertEntry(LedgerEntryEntity("d1", "c1", "DEBT", 5000, null, null, "", 2000))
        val bytes = PortableBackup.encode(dao.captureBackup(), JSONObject(), 5000)
        val file = File(app.cacheDir, "portable-import.raadbackup").apply { writeBytes(bytes) }
        val otherInstallation = LocalBackupEngine.forTesting(app, dao, BackupCrypto.newKey())
        val preview = otherInstallation.previewFile(Uri.fromFile(file), "")
        dao.deleteEntryById("d1")
        val result = otherInstallation.restore(preview)
        assertTrue(result.success); assertEquals(5000L, dao.getEntryById("d1")!!.amount)
        assertEquals("1", dao.restoreHold())
        file.delete()
    }
    @Test fun offlineDriveMirrorRemainsPendingAcrossRestartAndAcknowledgesLatestVerifiedFile() = runTest {
        val prefs = app.getSharedPreferences("raad_automatic_backup_v3", Context.MODE_PRIVATE)
        prefs.edit().putString("drive.account", "backup@example.com").putBoolean("drive.enabled", true).commit()
        val phone = Target("phone")
        val offline = AutomaticBackupEngine.forTesting(app, dao, phone, drive = { account ->
            assertEquals("backup@example.com", account)
            DeviceDriveBackup("test-token") { _, _ -> throw java.io.IOException("offline") }
        })
        dao.insertCustomer(customer); offline.process()
        assertFalse(offline.uploadDrive()); assertEquals(-1L, offline.state.value.drive.sequence)
        assertEquals(1L, offline.state.value.phone.sequence)
        dao.insertEntry(LedgerEntryEntity("d1", "c1", "DEBT", 5000, null, null, "", 2000)); offline.process()
        val restarted = AutomaticBackupEngine.forTesting(app, dao, phone, drive = { account ->
            assertEquals("backup@example.com", account)
            var calls = 0
            DeviceDriveBackup("test-token") { _, _ ->
                val artifact = phone.read(phone.list().first().locator)
                val response = if (calls++ == 0) "{\"files\":[{\"id\":\"uploaded\",\"size\":${artifact.size},\"md5Checksum\":\"${DeviceDriveBackup.md5(artifact)}\"}]}" else "{\"files\":[]}"
                object : HttpURLConnection(URL("https://www.googleapis.com/")) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode() = 200
                    override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
                }
            }
        })
        assertTrue(restarted.uploadDrive()); assertEquals(2L, restarted.state.value.drive.sequence)
        assertNull(restarted.state.value.drive.error)
    }
    @Test fun tornCardWriteCanRetryWithoutDestroyingPreviousFile() = runTest {
        val root = File(app.cacheDir, "portable-card-test").apply { deleteRecursively(); mkdirs() }
        val store = PrivateBackupStorage(root); val target = CardBackupTarget(store)
        val bytes = PortableBackup.encode(dao.captureBackup(), JSONObject(), 5000)
        val first = PortableBackup.name(5000, 0); target.write(first, bytes)
        val next = PortableBackup.name(6000, 0); store.write("Automatic", next, byteArrayOf(1, 2))
        target.write(next, bytes)
        assertArrayEquals(bytes, target.read(first)); assertArrayEquals(bytes, target.read(next))
        root.deleteRecursively()
    }
    @Test fun statusReportsPendingCopiesAndDisabledDestinationsHonestly() {
        assertEquals("بانتظار تحديث النسخة", automaticBackupStatus(AutomaticBackupPlace("phone", sequence = 1, updatedAt = 1), 2))
        assertEquals("غير مفعّل", automaticBackupStatus(AutomaticBackupPlace("sd", false, error = "خطأ"), 2))
        assertEquals("محدّث تلقائيًا", automaticBackupStatus(AutomaticBackupPlace("phone", sequence = 2, updatedAt = 1), 2))
    }
}

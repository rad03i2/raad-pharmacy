package com.radwan.raadpharmacy.backup

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneBackupTargetTest {
    private lateinit var app: Context
    private lateinit var provider: DownloadsProvider
    private lateinit var target: PhoneBackupTarget
    class DownloadsProvider : ContentProvider() {
        data class Row(val id: Long, val values: ContentValues, val file: File)
        val rows = linkedMapOf<Long, Row>()
        var corruptNextRead = false
        private var next = 1L
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = next++; val file = File(context!!.cacheDir, "media-backup-$id").apply { delete(); createNewFile() }
            rows[id] = Row(id, ContentValues(values!!), file)
            return ContentUris.withAppendedId(uri, id)
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = rows.getValue(ContentUris.parseId(uri)).file
            val actual = if (mode == "r" && corruptNextRead) {
                corruptNextRead = false
                File(context!!.cacheDir, "corrupt-read").apply { writeBytes(byteArrayOf(1, 2)) }
            } else file
            return ParcelFileDescriptor.open(actual, ParcelFileDescriptor.parseMode(mode))
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection!!.map { it }.toTypedArray()
            return MatrixCursor(columns).apply { rows.values.filter { it.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0 }.forEach { row ->
                addRow(columns.map<String, Any?> { when (it) {
                    MediaStore.MediaColumns._ID -> row.id
                    MediaStore.MediaColumns.DISPLAY_NAME -> row.values.getAsString(it)
                    MediaStore.MediaColumns.SIZE -> row.file.length()
                    else -> null
                } }.toTypedArray())
            } }
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            rows.getValue(ContentUris.parseId(uri)).values.putAll(values); return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            rows.remove(ContentUris.parseId(uri))?.file?.delete(); return 1
        }
    }
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        provider = DownloadsProvider().apply { attachInfo(app, ProviderInfo().apply { authority = "media" }) }
        ShadowContentResolver.registerProviderInternal("media", provider)
        target = PhoneBackupTarget(app)
    }
    private fun bytes(now: Long) = PortableBackup.encode(BackupCapture(emptyList(), emptyList(), emptyList(), now, false), JSONObject(), now)
    @Test fun createsPublicAppNamedFolderAndPublishesOnlyAfterReadBackVerification() {
        val payload = bytes(5000)
        val uri = target.write(PortableBackup.name(5000, 5000), payload)
        val row = provider.rows.values.single()
        assertEquals("Download/${PortableBackup.FOLDER}/", row.values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(0, row.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING).toInt())
        assertArrayEquals(payload, target.read(uri)); assertEquals(uri, target.list().single().locator)
    }
    @Test fun failedReadBackDeletesIncompleteNewFileAndKeepsPreviousCopy() {
        val previous = target.write(PortableBackup.name(5000, 5000), bytes(5000))
        provider.corruptNextRead = true
        assertThrows(Exception::class.java) { target.write(PortableBackup.name(6000, 6000), bytes(6000)) }
        assertEquals(previous, target.list().single().locator)
        assertEquals(5000L, PortableBackup.decode(target.read(previous)).sequence)
    }
    @Test fun retentionKeepsLatestAndTwoEarlierVerifiedFiles() {
        (5000L..5004L).forEach { target.write(PortableBackup.name(it, it), bytes(it)) }
        assertEquals(listOf(5004L, 5003L, 5002L), target.list().map { it.createdAt })
        assertEquals(3, provider.rows.size)
    }
}

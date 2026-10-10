package com.radwan.raadpharmacy.backup

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

/** Writes a new verified recovery point before pruning older files. Never truncates a good backup. */
internal interface PortableBackupTarget {
    fun write(name: String, bytes: ByteArray): String
    fun list(): List<PortableBackupItem>
    fun read(locator: String): ByteArray
}

internal class PhoneBackupTarget(private val context: Context) : PortableBackupTarget {
    private val resolver = context.contentResolver
    private val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/${PortableBackup.FOLDER}/"
    private val legacyFolder get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PortableBackup.FOLDER)
    private fun requireLegacyPermission() {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            "اسمح بحفظ النسخ على الهاتف مرة واحدة."
        }
    }
    override fun write(name: String, bytes: ByteArray): String {
        PortableBackup.decode(bytes)
        if (Build.VERSION.SDK_INT < 29) {
            requireLegacyPermission()
            val folder = legacyFolder.apply { check(isDirectory || mkdirs()) }
            val file = File(folder, name)
            check(file.createNewFile())
            try {
                FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
                check(file.readBytes().contentEquals(bytes))
                PortableBackup.decode(file.readBytes())
            } catch (error: Exception) { file.delete(); throw error }
            prune(file.absolutePath)
            return file.absolutePath
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("تعذر إنشاء النسخة. تحقق من المساحة المتاحة.")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes); it.flush() }
                ?: error("تعذر كتابة نسخة الهاتف.")
            val saved = read(uri.toString())
            check(saved.contentEquals(bytes)) { "لم يكتمل حفظ النسخة." }
            PortableBackup.decode(saved)
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        } catch (error: Exception) { runCatching { resolver.delete(uri, null, null) }; throw error }
        prune(uri.toString())
        return uri.toString()
    }
    override fun list(): List<PortableBackupItem> {
        if (Build.VERSION.SDK_INT < 29) {
            requireLegacyPermission()
            return legacyFolder.listFiles().orEmpty().filter { it.isFile && validName(it.name) }
                .map { item(it.absolutePath, it.name, it.length()) }.sortedByDescending { it.createdAt }
        }
        return resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
            arrayOf(relativePath), null)?.use { cursor -> buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    if (validName(name)) add(item(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)).toString(), name, cursor.getLong(2)))
                }
            }.sortedByDescending { it.createdAt }
        } ?: error("تعذر قراءة مجلد النسخ على الهاتف.")
    }
    override fun read(locator: String): ByteArray = if (locator.startsWith("content://"))
        resolver.openInputStream(Uri.parse(locator))?.use { it.readBackupBytes() } ?: error("تعذر فتح النسخة.")
    else File(locator).inputStream().use { it.readBackupBytes() }
    private fun prune(newLocator: String) {
        // Cleanup failure never invalidates an already verified recovery point.
        runCatching { list().filter { it.locator != newLocator }.drop(2).forEach {
            if (Build.VERSION.SDK_INT >= 29) resolver.delete(Uri.parse(it.locator), null, null)
            else File(it.locator).delete()
        } }
    }
    companion object {
        internal fun validName(name: String) = name.matches(Regex("RaadPharmacy-[0-9]+-[0-9]+\\.raadbackup"))
        internal fun item(locator: String, name: String, bytes: Long, place: String = "phone") =
            PortableBackupItem(place, locator, name, name.substringAfter("RaadPharmacy-").substringBefore('-').toLongOrNull() ?: 0, bytes)
    }
}

internal class CardBackupTarget(private val store: BackupStorage) : PortableBackupTarget {
    override fun write(name: String, bytes: ByteArray): String {
        PortableBackup.decode(bytes)
        if (store.list("Automatic").any { it.name == name }) {
            if (runCatching { store.read("Automatic", name).contentEquals(bytes) }.getOrDefault(false)) return name
            store.remove("Automatic", name) // Only discard this incomplete attempt, never the preceding good file.
        }
        try {
            store.write("Automatic", name, bytes)
            val saved = store.read("Automatic", name)
            check(saved.contentEquals(bytes)) { "لم يكتمل حفظ نسخة البطاقة." }
            PortableBackup.decode(saved)
        } catch (error: Exception) { runCatching { store.remove("Automatic", name) }; throw error }
        runCatching { list().filter { it.name != name }.drop(2).forEach { store.remove("Automatic", it.name) } }
        return name
    }
    override fun list() = store.list("Automatic").filter { PhoneBackupTarget.validName(it.name) }
        .map { PhoneBackupTarget.item(it.name, it.name, it.bytes, "sd") }.sortedByDescending { it.createdAt }
    override fun read(locator: String) = store.read("Automatic", locator)
}

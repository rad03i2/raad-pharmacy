package com.radwan.raadpharmacy.backup

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

internal data class StoredBackupFile(val name: String, val bytes: Long)
internal interface BackupStorage {
    fun list(area: String): List<StoredBackupFile>
    fun read(area: String, name: String): ByteArray
    fun write(area: String, name: String, bytes: ByteArray)
    fun remove(area: String, name: String)
}
internal fun InputStream.readBackupBytes(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(32 * 1024)
    while (true) {
        val n = read(buffer)
        if (n == -1) break
        require(out.size().toLong() + n <= BackupCrypto.MAX_FILE_BYTES + 36L) { "ملف النسخة أكبر من الحد الآمن." }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
internal class PrivateBackupStorage(private val root: File) : BackupStorage {
    private fun area(name: String) = File(root, name).apply { check(isDirectory || mkdirs()) }
    private fun path(area: String, name: String): File {
        require(name.matches(Regex("[a-zA-Z0-9._-]+")))
        return File(area(area), name)
    }
    override fun list(area: String) = this.area(area).listFiles().orEmpty().filter { it.isFile }
        .map { StoredBackupFile(it.name, it.length()) }
    override fun read(area: String, name: String) = path(area, name).inputStream().use { it.readBackupBytes() }
    override fun write(area: String, name: String, bytes: ByteArray) {
        val file = path(area, name)
        check(file.createNewFile()) { "ملف النسخة موجود بالفعل." }
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    override fun remove(area: String, name: String) { check(path(area, name).delete()) }
}

internal class TreeBackupStorage(private val context: Context, private val tree: Uri, rootId: String = DocumentsContract.getTreeDocumentId(tree)) : BackupStorage {
    private val resolver = context.contentResolver
    private val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
    private data class Child(val uri: Uri, val name: String, val mime: String, val bytes: Long)
    private fun children(parent: Uri): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        return resolver.query(uri, columns, null, null, null)?.use { cursor -> buildList {
            while (cursor.moveToNext()) add(Child(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                cursor.getString(1), cursor.getString(2), if (cursor.isNull(3)) 0 else cursor.getLong(3)))
        } } ?: error("المجلد غير متاح أو فُقد إذن الوصول إليه.")
    }
    private fun area(name: String): Uri = children(root).firstOrNull { it.name == name }?.also {
        check(it.mime == DocumentsContract.Document.MIME_TYPE_DIR)
    }?.uri ?: (DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, name)
        ?: error("تعذر إنشاء مجلد النسخ."))
    override fun list(area: String): List<StoredBackupFile> = children(this.area(area))
        .filter { it.mime != DocumentsContract.Document.MIME_TYPE_DIR }.map { StoredBackupFile(it.name, it.bytes) }
    override fun read(area: String, name: String): ByteArray {
        val doc = children(this.area(area)).singleOrNull { it.name == name } ?: error("ملف مفقود: $name")
        return resolver.openInputStream(doc.uri)?.use { it.readBackupBytes() } ?: error("تعذر قراءة النسخة.")
    }
    override fun write(area: String, name: String, bytes: ByteArray) {
        val folder = this.area(area)
        check(children(folder).none { it.name == name })
        val uri = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", name)
            ?: error("تعذر إنشاء ملف النسخة. تحقق من مساحة التخزين.")
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes); it.flush() } ?: error("تعذر كتابة النسخة.")
        // Providers need neither append nor atomic rename. Success requires reopening the saved file.
        check(children(folder).any { it.name == name }) { "مزود الملفات غيّر اسم النسخة." }
    }
    override fun remove(area: String, name: String) {
        val doc = children(this.area(area)).singleOrNull { it.name == name } ?: return
        check(DocumentsContract.deleteDocument(resolver, doc.uri))
    }
    companion object {
        fun validateLocalTree(context: Context, uri: Uri, sd: Boolean) {
            require(uri.authority == "com.android.externalstorage.documents") { "اختر مجلدًا محليًا على الهاتف أو بطاقة SD." }
            val volume = DocumentsContract.getTreeDocumentId(uri).substringBefore(':')
            if (sd) {
                val manager = context.getSystemService(StorageManager::class.java)
                require(manager.storageVolumes.any { it.isRemovable && it.uuid.equals(volume, true) && it.state == Environment.MEDIA_MOUNTED }) {
                    "اختر مجلدًا على بطاقة ذاكرة فعلية متاحة."
                }
            } else require(volume == "primary") { "اختر مجلدًا في ذاكرة الهاتف الداخلية." }
        }
        fun createRepository(context: Context, tree: Uri): Uri {
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val name = "Raad Pharmacy Backups-" + java.util.UUID.randomUUID().toString().take(8)
            val folder = DocumentsContract.createDocument(context.contentResolver, root, DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: error("تعذر إنشاء مجلد النسخ.")
            return DocumentsContract.buildTreeDocumentUri(tree.authority, DocumentsContract.getDocumentId(folder))
        }
    }
}

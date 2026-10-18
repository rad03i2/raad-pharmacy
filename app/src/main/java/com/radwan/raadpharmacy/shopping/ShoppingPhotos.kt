package com.radwan.raadpharmacy.shopping

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** No Storage, ledger-photo DAO, backup directory, URL, base64 field or cloud uploader is used. */
internal class ShoppingPhotos(private val context: Context,
    private val root: File = File(context.noBackupFilesDir,"medicine-shopping")) {
    private fun uuid(value: String): String = UUID.fromString(value).toString()
    fun draft(id: String) = File(File(root,"drafts"),"${uuid(id)}.jpg")
    fun file(pharmacy: String,id: String) = File(File(root,"photos/${uuid(pharmacy)}"),"${uuid(id)}.jpg")
    fun import(id: String, uri: Uri) {
        val target=draft(id)
        target.parentFile!!.mkdirs()
        val raw=File(target.parentFile,"${uuid(id)}.source")
        val encoded=File(target.parentFile,"${uuid(id)}.writing")
        var bitmap: Bitmap?=null
        var scaled: Bitmap?=null
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "تعذر قراءة الصورة المختارة." }
                FileOutputStream(raw).use { output ->
                    val buffer=ByteArray(16*1024); var total=0L
                    while(true) { val count=input.read(buffer); if(count<0) break
                        total+=count; require(total<=25L*1024*1024) { "الصورة أكبر من 25 ميغابايت؛ اختر صورة أصغر." }
                        output.write(buffer,0,count)
                    }
                }
            }
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFile(raw.path,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0) { "الملف ليس صورة صالحة أو تنسيقه غير مدعوم." }
            var sample=1
            while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048) sample*=2
            bitmap=requireNotNull(BitmapFactory.decodeFile(raw.path,BitmapFactory.Options().apply { inSampleSize=sample })) { "تعذر فتح الصورة." }
            val ratio=minOf(1f,1600f/maxOf(bitmap.width,bitmap.height))
            scaled=if(ratio<1) Bitmap.createScaledBitmap(bitmap,(bitmap.width*ratio).toInt().coerceAtLeast(1),
                (bitmap.height*ratio).toInt().coerceAtLeast(1),true) else bitmap
            FileOutputStream(encoded).use { output -> check(scaled.compress(Bitmap.CompressFormat.JPEG,88,output)); output.fd.sync() }
            check(encoded.renameTo(target)) { "تعذر حفظ الصورة على الهاتف." }
        } finally {
            if(scaled!==bitmap) scaled?.recycle(); bitmap?.recycle(); raw.delete(); encoded.delete()
        }
    }
    fun attach(pharmacy: String,id: String) {
        val source=draft(id); require(source.isFile) { "اختر الصورة مرة أخرى." }
        val target=file(pharmacy,id)
        target.parentFile!!.mkdirs()
        FileOutputStream(target).use { output -> source.inputStream().use { it.copyTo(output) }; output.fd.sync() }
    }
    fun discardDraft(id: String) { draft(id).delete() }
    fun delete(pharmacy: String,id: String) { file(pharmacy,id).delete() }
    fun reconcile(local: ShoppingLocalStore) {
        File(root,"photos").listFiles()?.filter { it.isDirectory }?.forEach { folder ->
            val keep=local.activePhotoIds(folder.name)
            folder.listFiles()?.filter { it.nameWithoutExtension !in keep }?.forEach { it.delete() }
        }
        // Abandoned unsaved drafts have never been shared. Active list images have no expiry.
        File(root,"drafts").listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>7L*24*60*60*1000 }?.forEach { it.delete() }
    }
}

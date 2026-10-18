package com.radwan.raadpharmacy.shopping

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShoppingPhotosTest {
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private lateinit var root: File
    private lateinit var photos: ShoppingPhotos
    private lateinit var local: ShoppingLocalStore
    private val identity=ShoppingIdentity("00000000-0000-4000-8000-000000000011","00000000-0000-4000-8000-000000000001")
    private val id="00000000-0000-4000-8000-000000000071"
    @Before fun open() {root=File(context.noBackupFilesDir,"shopping-photos-${UUID.randomUUID()}");photos=ShoppingPhotos(context,root);local=ShoppingLocalStore(context,root)}
    @After fun close() {local.close();root.deleteRecursively()}
    private fun source(): File {
        val file=File(root.apply{mkdirs()},"source.jpg")
        val bitmap=Bitmap.createBitmap(4000,2000,Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.CYAN)}
        file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it))};bitmap.recycle();return file
    }
    @Test fun importingNormalizesSizeAndExifRotationWithoutUploadingOrMovingTheOriginal() {
        val source=source()
        ExifInterface(source.path).apply {setAttribute(ExifInterface.TAG_ORIENTATION,"6");setAttribute(ExifInterface.TAG_GPS_LATITUDE,"33/1,0/1,0/1");saveAttributes()}
        photos.import(id,Uri.fromFile(source))
        val image=BitmapFactory.decodeFile(photos.draft(id).path)
        assertTrue(image.width<image.height);assertTrue(maxOf(image.width,image.height)<=1600);image.recycle()
        assertNull(ExifInterface(photos.draft(id).path).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        photos.attach(identity.pharmacy,id);local.add(identity,ShoppingItem(id,"صورة علاج",hasPhoto=true))
        photos.discardDraft(id)
        assertTrue(photos.file(identity.pharmacy,id).canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertTrue(source.isFile);assertFalse(photos.draft(id).exists())
    }
    @Test fun invalidAndTooLargeSourcesLeaveExistingGoodDraftIntact() {
        photos.import(id,Uri.fromFile(source()));val before=photos.draft(id).readBytes()
        val bad=File(root,"invalid.txt").apply {writeText("not an image")}
        assertThrows(IllegalArgumentException::class.java) {photos.import(id,Uri.fromFile(bad))}
        assertArrayEquals(before,photos.draft(id).readBytes())
        val huge=File(root,"large.bin");RandomAccessFile(huge,"rw").use {it.setLength(26L*1024*1024)}
        assertThrows(IllegalArgumentException::class.java) {photos.import(id,Uri.fromFile(huge))}
        assertArrayEquals(before,photos.draft(id).readBytes())
        assertFalse(photos.draft(id).parentFile!!.listFiles().orEmpty().any {it.extension in setOf("source","writing")})
    }
    @Test fun deletionOrRemoteAbsenceRemovesPhotosEvenAfterAnInterruptedCleanup() {
        photos.import(id,Uri.fromFile(source()));photos.attach(identity.pharmacy,id)
        local.add(identity,ShoppingItem(id,"علاج",hasPhoto=true))
        photos.reconcile(local);assertTrue(photos.file(identity.pharmacy,id).isFile)
        local.complete(identity,id) // Simulate process death between durable delete intent and file deletion.
        photos.reconcile(local);assertFalse(photos.file(identity.pharmacy,id).exists())
        val peer=UUID.randomUUID().toString();photos.import(peer,Uri.fromFile(source()));photos.attach(identity.pharmacy,peer)
        val row=ShoppingItem(peer,"مشترك",hasPhoto=true)
        local.merge(identity,ShoppingSnapshot(identity.owner,identity.pharmacy,listOf(row)))
        photos.reconcile(local);assertTrue(photos.file(identity.pharmacy,peer).isFile)
        local.merge(identity,ShoppingSnapshot(identity.owner,identity.pharmacy,emptyList()))
        photos.reconcile(local);assertFalse(photos.file(identity.pharmacy,peer).exists())
    }
    @Test fun identifiersCannotEscapePrivatePhotoDirectory() {
        assertThrows(IllegalArgumentException::class.java) {photos.file("../outside",id)}
        assertThrows(IllegalArgumentException::class.java) {photos.draft("../../outside")}
    }
}

package com.radwan.raadpharmacy.customer

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomerAvatarImagesTest {
    @Test fun largeCustomerPhotosAreDownsampledAndReusedUntilTheirRevisionChanges() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val id=UUID.randomUUID().toString()
        val source=Bitmap.createBitmap(1600,1200,Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.RED)
        val bytes=ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.JPEG,90,it) }.toByteArray()
        CustomerPhotoStore(context).saveRemote(id,"photo-one",bytes)
        val first=CustomerAvatarImages.load(context,id,1L)!!
        assertTrue(first.width<=256 && first.height<=256)
        assertSame(first,CustomerAvatarImages.load(context,id,1L))
        assertNull(CustomerAvatarImages.cached(id,2L))
        source.eraseColor(android.graphics.Color.BLUE)
        val updated=ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.JPEG,90,it) }.toByteArray()
        CustomerPhotoStore(context).saveRemote(id,"photo-two",updated)
        val next=CustomerAvatarImages.load(context,id,2L)!!
        assertNotSame(first,next)
        assertTrue(android.graphics.Color.blue(next.getPixel(0,0)) > android.graphics.Color.red(next.getPixel(0,0)))
    }
    @Test fun largeCameraImageIsProtectedBeforeBeingMirroredAndFitsRoomRows() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = UUID.randomUUID().toString()
        val dao = com.radwan.raadpharmacy.data.PharmacyLedgerDatabase.get(context).dao()
        kotlinx.coroutines.runBlocking { dao.insertCustomer(com.radwan.raadpharmacy.data.CustomerEntity(id, "صورة كبيرة", null, "", "", 0, "", 1000)) }
        val random = java.util.Random(7)
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(1600 * 1200) { random.nextInt() or 0xff000000.toInt() }, 0, 1600, 0, 0, 1600, 1200)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 99, it) }.toByteArray()
        assertTrue(bytes.size > CustomerPhotoStore.MAX_BACKUP_PHOTO_BYTES)
        val file = CustomerPhotoStore(context).saveRemote(id, "large-photo", bytes)
        val protected = kotlinx.coroutines.runBlocking { dao.backupPhoto(id)!! }
        assertTrue(protected.bytes.size <= CustomerPhotoStore.MAX_BACKUP_PHOTO_BYTES)
        assertArrayEquals(protected.bytes, file.readBytes())
        bitmap.recycle()
    }

}

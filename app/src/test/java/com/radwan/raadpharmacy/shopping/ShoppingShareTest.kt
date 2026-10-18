package com.radwan.raadpharmacy.shopping

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
class ShoppingShareTest {
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private val inbox get()=ShoppingShareInbox(context)
    private lateinit var source: File
    private var request=UUID.randomUUID().toString()
    @Before fun image() {
        source=File(context.cacheDir,"shopping_camera/share-fixture.jpg").apply {parentFile!!.mkdirs()}
        val bitmap=Bitmap.createBitmap(900,500,Bitmap.Config.ARGB_8888)
        source.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle()
    }
    @After fun cleanup() {if(inbox.exists(request)) inbox.discard(request);source.delete()}
    private fun intent(quick: Boolean)=Intent(Intent.ACTION_SEND).setType("image/jpeg")
        .setComponent(ComponentName(context,if(quick) "${context.packageName}.shopping.ShoppingQuickShare" else ShoppingShareActivity::class.java.name))
        .putExtra(Intent.EXTRA_STREAM,FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",source))
    @Test fun normalAndQuickShareCopyValidContentUriAndHaveStableIdsAcrossRecreation() {
        val original=intent(true)
        val first=inbox.receive(original,request)
        assertTrue(first.quick);assertEquals(1,first.ids.size);assertEquals(first,inbox.read(request))
        source.delete() // Subsequent receipt does not require a still-valid external permission.
        assertEquals(first,inbox.receive(original,request))
        assertTrue(ShoppingPhotos(context).draft(first.ids.single()).isFile)
    }
    @Test fun detailsTargetDoesNotBecomeSilentAndBothTargetsResolveForImages() {
        val normal=inbox.receive(intent(false),request);assertFalse(normal.quick)
        val targets=context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("image/png"),0)
        assertTrue(targets.any {it.activityInfo.name.endsWith("ShoppingShareActivity")})
        assertTrue(targets.any {it.activityInfo.name.endsWith("ShoppingQuickShare")})
    }
    @Test fun invalidShareDoesNotCreateAnIntakeOrReadArbitraryLocalFile() {
        assertThrows(IllegalArgumentException::class.java) {inbox.receive(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM,android.net.Uri.fromFile(source)),request)}
        assertFalse(inbox.exists(request))
        source.writeText("not an image")
        assertThrows(Exception::class.java) {inbox.receive(intent(false),request)}
        assertFalse(inbox.exists(request))
    }
}

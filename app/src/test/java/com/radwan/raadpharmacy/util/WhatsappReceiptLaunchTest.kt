package com.radwan.raadpharmacy.util

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WhatsappReceiptLaunchTest {
    private class RecordingContext(activity: Activity, private val ordinaryMissing: Boolean = false) : ContextWrapper(activity) {
        val launched = mutableListOf<Intent>()
        val grants = mutableListOf<Pair<String, Uri>>()
        override fun grantUriPermission(toPackage: String, uri: Uri, modeFlags: Int) {
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, modeFlags)
            grants.add(toPackage to uri)
        }
        override fun startActivity(intent: Intent) {
            if (ordinaryMissing && intent.`package` == "com.whatsapp") throw ActivityNotFoundException()
            launched.add(Intent(intent))
        }
    }

    @Test fun actualLaunchRetainsEachRecipientsReadableImageAndCaptionAcrossThreeShares() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = RecordingContext(activity)
        val files = listOf(Color.RED, Color.GREEN, Color.BLUE).map { color ->
            File.createTempFile("receipt-", ".png", File(activity.cacheDir, "shared").apply { mkdirs() }).apply {
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color)
                outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        try {
            val phones = listOf("07701111111", "+٩٦٤٧٧٠٢٢٢٢٢٢٢", "009647703333333")
            phones.forEachIndexed { i, phone ->
                StatementShare.shareImageToWhatsappContact(context, files[i], "وصل الزبون ${i + 1}", phone)
            }
            assertEquals(3, context.launched.size)
            context.launched.forEachIndexed { i, intent ->
                assertEquals(Intent.ACTION_SEND, intent.action)
                assertEquals("com.whatsapp", intent.`package`)
                assertEquals("${validatedIraqiWhatsappPhone(phones[i])}@s.whatsapp.net", intent.getStringExtra("jid"))
                assertEquals("وصل الزبون ${i + 1}", intent.getStringExtra(Intent.EXTRA_TEXT))
                val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
                assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
                assertArrayEquals(files[i].readBytes(), activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                assertEquals("com.whatsapp" to uri, context.grants[i])
                assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
                assertFalse(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
                assertFalse(intent.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT != 0)
                assertFalse(intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
            }
            assertEquals(3, context.launched.map { it.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) }.distinct().size)
        } finally {
            files.forEach { it.delete() }
            activity.finish()
        }
    }

    @Test fun businessFallbackKeepsTheSameRecipientMediaAndCaption() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = RecordingContext(activity, ordinaryMissing = true)
        val file = File.createTempFile("receipt-", ".png", File(activity.cacheDir, "shared").apply { mkdirs() })
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            StatementShare.shareImageToWhatsappContact(context, file, "وصل Business", "07701234567")
            val intent = context.launched.single()
            assertEquals("com.whatsapp.w4b", intent.`package`)
            assertEquals("9647701234567@s.whatsapp.net", intent.getStringExtra("jid"))
            assertEquals("وصل Business", intent.getStringExtra(Intent.EXTRA_TEXT))
            val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
            assertArrayEquals(file.readBytes(), activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally {
            file.delete()
            activity.finish()
        }
    }
}

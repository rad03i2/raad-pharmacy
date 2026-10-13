package com.radwan.raadpharmacy.util

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TargetedWhatsappIntentTest {
    @Test fun ordinaryWhatsAppCarriesMediaCaptionAndTarget() {
        assertPrepared("com.whatsapp")
    }

    @Test fun businessWhatsAppCarriesMediaCaptionAndTarget() {
        assertPrepared("com.whatsapp.w4b")
    }

    private fun assertPrepared(packageName: String) {
        val uri = Uri.parse("content://com.radwan.raadpharmacy.fileprovider/receipt.png")
        val caption = "تذكير بعد أسبوع — رصيد الحساب 25,000"
        val intent = StatementShare.targetedWhatsappIntent(
            uri, caption, "9647701234567", packageName
        )
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        assertEquals(packageName, intent.`package`)
        assertNull(intent.component)
        assertEquals("9647701234567@s.whatsapp.net", intent.getStringExtra("jid"))
        assertEquals(caption, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertNotNull(intent.clipData)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}

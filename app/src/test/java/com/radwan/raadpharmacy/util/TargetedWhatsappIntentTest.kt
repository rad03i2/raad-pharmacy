package com.radwan.raadpharmacy.util

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TargetedWhatsappIntentTest {
    @Test fun attachmentAndTextCarrySpecifiedWhatsAppRecipient() {
        val uri = Uri.parse("content://com.radwan.raadpharmacy.fileprovider/mock.png")
        val intent = StatementShare.targetedWhatsappIntent(uri, "كشف الزبون", "9647701234567", "com.whatsapp")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("com.whatsapp", intent.component?.packageName)
        assertEquals("com.whatsapp.Conversation", intent.component?.className)
        assertEquals("9647701234567@s.whatsapp.net", intent.getStringExtra("jid"))
        assertEquals("كشف الزبون", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertNotNull(intent.clipData)
    }
}

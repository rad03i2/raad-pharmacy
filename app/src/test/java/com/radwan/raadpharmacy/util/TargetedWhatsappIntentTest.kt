package com.radwan.raadpharmacy.util

import android.content.Intent
import android.app.Activity
import android.content.ContextWrapper
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
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

    @Test fun threeConsecutiveSharesKeepFreshTextRecipientAndImage() {
        val picture = Uri.parse("content://com.radwan.raadpharmacy.fileprovider/a.png")
        val cases = listOf(
            Triple("9647701111111", "رسالة بعد يوم", picture),
            Triple("9647702222222", "رسالة بعد أسبوع", Uri.parse("content://com.radwan.raadpharmacy.fileprovider/b.png")),
            Triple("9647703333333", "رسالة بعد 30 يومًا", Uri.parse("content://com.radwan.raadpharmacy.fileprovider/c.png"))
        )
        val intents = cases.map { (number, caption, uri) ->
            StatementShare.targetedWhatsappIntent(uri, caption, number, "com.whatsapp")
        }
        cases.forEachIndexed { index, (number, caption, uri) ->
            val sent = intents[index]
            assertEquals("com.whatsapp", sent.`package`)
            assertEquals("$number@s.whatsapp.net", sent.getStringExtra("jid"))
            assertEquals(caption, sent.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(uri, sent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            assertFalse(sent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
        assertEquals(3, intents.map { it.getStringExtra("jid") }.distinct().size)
        assertEquals(3, intents.map { it.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) }.distinct().size)
    }

    @Test fun wrappedActivityStillCountsAsActivityContext() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertTrue(StatementShare.hasActivityContext(ContextWrapper(activity)))
        assertFalse(StatementShare.hasActivityContext(activity.applicationContext))
    }

    @Test fun everyWhatsappShareIsANewAndroidDocumentWithCurrentPayload() {
        val contacts = listOf(
            Triple("9647701111111", "تذكير بعد يوم", "first.png"),
            Triple("9647702222222", "تذكير بعد أسبوع", "second.png"),
            Triple("9647703333333", "تذكير بعد شهر", "third.png")
        )
        contacts.forEach { (phone, caption, fileName) ->
            val uri = Uri.parse("content://com.radwan.raadpharmacy.fileprovider/$fileName")
            val intent = StatementShare.freshTargetedWhatsappIntent(uri, caption, phone, "com.whatsapp")
            assertEquals("$phone@s.whatsapp.net", intent.getStringExtra("jid"))
            assertEquals(caption, intent.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT != 0)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
    }

    @Test fun fallbackAlwaysPreservesImageAndTextButNeedsManualRecipientConfirmation() {
        val uri = Uri.parse("content://com.radwan.raadpharmacy.fileprovider/statement.png")
        val intent = StatementShare.pickerWhatsappIntent(uri, "المبلغ المتبقي: 50000", "com.whatsapp.w4b")
        assertNull(intent.getStringExtra("jid"))
        assertEquals("com.whatsapp.w4b", intent.`package`)
        assertEquals("المبلغ المتبقي: 50000", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
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
        assertFalse(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(intent.component)
        assertEquals("9647701234567@s.whatsapp.net", intent.getStringExtra("jid"))
        assertEquals(caption, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertNotNull(intent.clipData)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}

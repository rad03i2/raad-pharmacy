package com.radwan.raadpharmacy.util

import android.content.ClipData
import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.FileProvider
import java.io.File

object StatementShare {
    fun shareImage(
        context: Context,
        file: File,
        message: String,
        whatsappOnly: Boolean
    ) {
        shareFile(context, file, "image/png", message, whatsappOnly, null)
    }

    /**
     * Share the image AND editable message to WhatsApp in one ACTION_SEND.
     *
     * The previous version forced WhatsApp's internal Conversation activity:
     * it opened the intended chat but ignored EXTRA_STREAM and EXTRA_TEXT.
     * ACTION_SEND with setPackage (not setComponent) lets WhatsApp's own
     * share receiver process the binary content and its caption.
     *
     * "jid" is WhatsApp-specific, and its handling differs by installed
     * version. The recipient may still need manual confirmation; we must
     * never fall back to a wa.me link because that loses the attachment.
     */
    fun shareImageToWhatsappContact(
        context: Context,
        file: File,
        message: String,
        phone: String
    ) {
        val normalized = validatedIraqiWhatsappPhone(phone)
            ?: throw IllegalArgumentException("رقم الزبون غير صالح لفتح محادثة واتساب.")
        require(file.isFile && file.length() > 0L) { "صورة كشف الحساب غير موجودة أو فارغة." }
        require(message.isNotBlank()) { "نص كشف الحساب فارغ." }
        val uri = contentUri(context, file)

        val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
        for (pkg in packages) {
            val intent = freshTargetedWhatsappIntent(uri, message, normalized, pkg)
            if (runCatching {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(intent)
            }.isSuccess) return
        }
        throw IllegalStateException("تعذر فتح مشاركة واتساب بصورة الكشف والنص. تأكد من تثبيت واتساب.")
    }

    /**
     * Reliable media fallback: WhatsApp's documented Android sharing API lets
     * WhatsApp pick the recipient. This intentionally omits the unsupported JID.
     * The caller must check the recipient within WhatsApp before tapping send.
     */
    fun shareImageToWhatsappPicker(
        context: Context, file: File, message: String
    ) {
        require(file.isFile && file.length() > 0L) { "صورة الكشف غير موجودة." }
        require(message.isNotBlank()) { "رسالة كشف الحساب فارغة." }
        val uri = contentUri(context, file)
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val intent = pickerWhatsappIntent(uri, message, pkg)
            val launched = runCatching {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(intent)
            }.isSuccess
            if (launched) return
        }
        throw IllegalStateException("لا يمكن فتح واتساب لمشاركة صورة الكشف.")
    }

    internal fun freshTargetedWhatsappIntent(
        uri: Uri, text: String, phone: String, pkg: String
    ): Intent = targetedWhatsappIntent(uri, text, phone, pkg).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }

    internal fun pickerWhatsappIntent(uri: Uri, text: String, pkg: String): Intent =
        targetedWhatsappIntent(uri, text, "", pkg).apply {
            removeExtra("jid")
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }

    internal fun targetedWhatsappIntent(uri: Uri, text: String, phone: String, pkg: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            setPackage(pkg)
            putExtra("jid", "$phone@s.whatsapp.net")
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newRawUri("statement", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    internal fun hasActivityContext(context: Context): Boolean {
        var current: Context = context
        val visited = mutableSetOf<Context>()
        while (visited.add(current)) {
            if (current is Activity) return true
            current = (current as? ContextWrapper)?.baseContext ?: return false
        }
        return false
    }

    fun shareImageToMessages(
        context: Context,
        file: File,
        message: String,
        phone: String
    ) {
        val uri = contentUri(context, file)
        val normalizedPhone = normalizeIraqPhone(phone)
        require(normalizedPhone.isNotBlank()) { "رقم الهاتف غير صالح." }

        val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(context)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, message)
            putExtra("sms_body", message)
            putExtra("address", normalizedPhone)
            clipData = ClipData.newRawUri("statement", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            defaultSmsPackage?.let(::setPackage)
        }

        runCatching {
            context.startActivity(intent)
        }.onFailure {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, message)
                        clipData = ClipData.newRawUri("statement", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "إرسال كشف الحساب عبر الرسائل"
                )
            )
        }
    }

    private fun shareFile(
        context: Context,
        file: File,
        mimeType: String,
        message: String,
        whatsappOnly: Boolean,
        whatsappJid: String?
    ) {
        val uri = contentUri(context, file)

        fun buildIntent(packageName: String? = null): Intent =
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, message)
                clipData = ClipData.newRawUri("statement", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                packageName?.let(::setPackage)
                whatsappJid?.let { putExtra("jid", it) }
            }

        if (whatsappOnly) {
            val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
            val launched = packages.any { packageName ->
                runCatching {
                    context.startActivity(buildIntent(packageName))
                    true
                }.getOrDefault(false)
            }
            if (!launched) {
                context.startActivity(
                    Intent.createChooser(
                        buildIntent(),
                        "مشاركة كشف الحساب"
                    )
                )
            }
        } else {
            context.startActivity(
                Intent.createChooser(
                    buildIntent(),
                    "مشاركة كشف الحساب"
                )
            )
        }
    }

    private fun contentUri(
        context: Context,
        file: File
    ) = FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        file
    )
}

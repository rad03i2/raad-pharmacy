package com.radwan.raadpharmacy.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
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

    fun shareImageToWhatsappContact(
        context: Context,
        file: File,
        message: String,
        phone: String
    ) {
        val normalized = normalizeIraqPhone(phone)
            .filter(Char::isDigit)
            .removePrefix("00")
        require(normalized.isNotBlank()) { "رقم الهاتف غير صالح." }
        shareFile(
            context = context,
            file = file,
            mimeType = "image/png",
            message = message,
            whatsappOnly = true,
            whatsappJid = normalized + "@s.whatsapp.net"
        )
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

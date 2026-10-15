package com.radwan.raadpharmacy.whatsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat
import com.radwan.raadpharmacy.R
import com.radwan.raadpharmacy.data.DebtFollowupEngine
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.data.LedgerEntry
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate

/** Compact account summary, explicitly not a substitute for the complete statement. */
internal object AutomaticWhatsappReceiptImage {
    fun create(
        context: Context, account: DebtFollowupEngine.Account, entries: List<LedgerEntry>
    ): PhoneWhatsappTestImage.Payload {
        val bitmap = Bitmap.createBitmap(960, 1010, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(248, 250, 252))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.RIGHT
                typeface = ResourcesCompat.getFont(context, R.font.tajawal_bold)
                color = Color.rgb(27, 79, 65)
                textSize = 38f
            }
            fun row(label: String, y: Float, size: Float = 29f, strong: Boolean = false) {
                paint.textSize = size
                paint.color = if (strong) Color.rgb(24, 88, 64) else Color.rgb(45, 55, 72)
                canvas.drawText(label, 890f, y, paint)
            }
            row("صيدلية رعد — ملخص حساب", 88f, 45f, true)
            row("الاسم: ${account.customer.name.take(55)}", 165f)
            row("المتبقي: ${"%,d".format(java.util.Locale.US, account.balance)} دينار عراقي", 225f, 36f, true)
            row("تاريخ الكشف: ${LocalDate.now(DebtFollowupEngine.IRAQ_ZONE)}", 276f)
            row("آخر الحركات المالية", 362f, 35f, true)
            var y = 420f
            entries.asSequence().filter { it.customerId == account.customer.id }
                .sortedWith(compareByDescending<LedgerEntry> { it.createdAt }.thenByDescending { it.id })
                .take(9).forEach { entry ->
                    val date = runCatching { Instant.ofEpochMilli(entry.createdAt).atZone(DebtFollowupEngine.IRAQ_ZONE)
                        .toLocalDate().toString() }.getOrDefault("—")
                    val kind = if (entry.type == EntryType.DEBT) "دين" else "تسديد"
                    row("$date • $kind • ${"%,d".format(java.util.Locale.US, entry.amount)} د.ع", y)
                    y += 55f
                }
            row("تظهر آخر 9 حركات فقط. يُرجى طلب كشف الحساب الكامل عند الحاجة.", 952f, 23f)
            val image = ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 72, out)); out.toByteArray()
            }
            val thumbBitmap = Bitmap.createScaledBitmap(bitmap, 144, 152, true)
            val thumb = try {
                ByteArrayOutputStream().use { out ->
                    check(thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 55, out)); out.toByteArray()
                }
            } finally { thumbBitmap.recycle() }
            require(image.size in 1..180_000 && thumb.size in 1..25_000)
            return PhoneWhatsappTestImage.Payload(image, thumb)
        } finally { bitmap.recycle() }
    }
}

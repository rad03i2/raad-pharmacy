package com.radwan.raadpharmacy.whatsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat
import com.radwan.raadpharmacy.R
import java.io.ByteArrayOutputStream

/** Synthetic media only: the trial never reads a customer or financial ledger. */
internal object PhoneWhatsappTestImage {
    data class Payload(val image: ByteArray, val thumbnail: ByteArray)
    fun create(context: Context): Payload {
        val bitmap = Bitmap.createBitmap(720, 420, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(247, 249, 251))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = ResourcesCompat.getFont(context, R.font.tajawal_bold)
                color = Color.rgb(17, 92, 73)
                textSize = 44f
            }
            canvas.drawText("صيدلية رعد", 360f, 112f, paint)
            paint.textSize = 35f
            paint.color = Color.rgb(31, 41, 55)
            canvas.drawText("تجربة مساعد واتساب", 360f, 205f, paint)
            paint.textSize = 27f
            canvas.drawText("اختبار وصول الصورة والنص", 360f, 282f, paint)
            paint.textSize = 23f
            paint.color = Color.rgb(75, 85, 99)
            canvas.drawText("المرحلة الأولى: الربط والإرسال", 360f, 350f, paint)
            val image = ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)); out.toByteArray()
            }
            val small = Bitmap.createScaledBitmap(bitmap, 144, 84, true)
            val thumbnail = try {
                ByteArrayOutputStream().use { out ->
                    check(small.compress(Bitmap.CompressFormat.JPEG, 65, out)); out.toByteArray()
                }
            } finally { small.recycle() }
            require(image.size <= 180_000 && thumbnail.size <= 25_000)
            return Payload(image, thumbnail)
        } finally { bitmap.recycle() }
    }
}

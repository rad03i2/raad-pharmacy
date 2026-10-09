package com.radwan.raadpharmacy.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.radwan.raadpharmacy.R
import com.radwan.raadpharmacy.customer.CustomerPhotoStore
import com.radwan.raadpharmacy.data.Customer
import com.radwan.raadpharmacy.data.EntryType
import com.radwan.raadpharmacy.data.LedgerEntry
import com.radwan.raadpharmacy.ui.theme.AppFontPreset
import com.radwan.raadpharmacy.ui.theme.TypographyPreferences
import java.io.File
import java.io.FileOutputStream

data class StatementSnapshot(
    val customer: Customer,
    val entries: List<LedgerEntry>,
    val currentBalance: Long,
    val totalDebts: Long,
    val totalPaid: Long,
    val generatedAt: Long = System.currentTimeMillis()
)

object StatementDocumentRenderer {
    const val WIDTH = 1440
    const val HEIGHT = 1920

    private val green = Color.rgb(37, 99, 235)
    private val greenDark = Color.rgb(30, 58, 138)
    private val greenSoft = Color.rgb(230, 247, 248)
    private val gold = Color.rgb(14, 165, 168)
    private val red = Color.rgb(220, 38, 38)
    private val redSoft = Color.rgb(254, 226, 226)
    private val ink = Color.rgb(15, 23, 42)
    private val muted = Color.rgb(100, 116, 139)
    private val line = Color.rgb(226, 232, 240)
    private val warmBackground = Color.rgb(247, 249, 252)

    fun createPng(
        context: Context,
        snapshot: StatementSnapshot
    ): File {
        val bitmap = render(context, snapshot)
        val file = File(shareDir(context), fileName(snapshot, "png"))
        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        return file
    }

    fun message(snapshot: StatementSnapshot): String = buildString {
        appendLine("السلام عليكم")
        appendLine("كشف حساب الدين")
        appendLine("الزبون: " + snapshot.customer.name)
        appendLine("الدين الحالي: " + formatMoney(snapshot.currentBalance))
        appendLine("شكرًا لحسن تعاملكم 🌹")
    }

    fun currentCycleEntries(snapshot: StatementSnapshot): List<LedgerEntry> {
        if (snapshot.currentBalance <= 0L) return emptyList()

        val ordered = snapshot.entries.sortedBy { it.createdAt }
        var running = snapshot.customer.openingDebt.coerceAtLeast(0L)
        var cycleStart = 0

        ordered.forEachIndexed { index, entry ->
            running = when (entry.type) {
                EntryType.DEBT -> running + entry.amount
                EntryType.PAYMENT -> (running - entry.amount).coerceAtLeast(0L)
            }
            if (running == 0L) cycleStart = index + 1
        }

        return ordered
            .drop(cycleStart)
            .sortedByDescending { it.createdAt }
    }

    private fun render(
        context: Context,
        snapshot: StatementSnapshot
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(
            WIDTH,
            HEIGHT,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        val selectedFont = TypographyPreferences(context).state().font
        val fontResource = when (selectedFont) {
            AppFontPreset.CAIRO -> R.font.cairo_variable
            AppFontPreset.TAJAWAL -> R.font.tajawal_regular
            AppFontPreset.NOTO_KUFI_ARABIC -> R.font.noto_kufi_arabic_variable
            AppFontPreset.NOTO_SANS_ARABIC -> R.font.noto_sans_arabic_variable
        }
        val baseTypeface = runCatching {
            ResourcesCompat.getFont(context, fontResource)
        }.getOrNull() ?: Typeface.DEFAULT

        val regular = Typeface.create(baseTypeface, Typeface.NORMAL)
        val bold = Typeface.create(baseTypeface, Typeface.BOLD)
        val painter = StatementPainter(
            canvas = canvas,
            regular = regular,
            bold = bold
        )

        canvas.drawColor(warmBackground)

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                52f,
                52f,
                (WIDTH - 52).toFloat(),
                348f,
                intArrayOf(greenDark, green),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(
            52f,
            52f,
            (WIDTH - 52).toFloat(),
            358f,
            50f,
            50f,
            headerPaint
        )

        val ornament = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(24, 255, 255, 255)
        }
        canvas.drawCircle(165f, 305f, 128f, ornament)
        canvas.drawCircle((WIDTH - 148).toFloat(), 112f, 92f, ornament)

        val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold }
        canvas.drawRoundRect(
            190f,
            337f,
            (WIDTH - 190).toFloat(),
            345f,
            4f,
            4f,
            goldPaint
        )

        painter.text(
            value = "صيدلية رعد",
            x = 100,
            y = 88,
            width = WIDTH - 200,
            size = 62f,
            color = Color.WHITE,
            isBold = true,
            alignment = Layout.Alignment.ALIGN_CENTER
        )
        painter.text(
            value = "كشف حساب الدين",
            x = 100,
            y = 178,
            width = WIDTH - 200,
            size = 40f,
            color = Color.WHITE,
            isBold = true,
            alignment = Layout.Alignment.ALIGN_CENTER
        )
        painter.text(
            value = "تاريخ الكشف  •  " + formatDate(snapshot.generatedAt),
            x = 100,
            y = 251,
            width = WIDTH - 200,
            size = 29f,
            color = Color.rgb(218, 239, 233),
            alignment = Layout.Alignment.ALIGN_CENTER
        )

        val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }

        val customerTop = 402
        canvas.drawRoundRect(
            70f,
            customerTop.toFloat(),
            (WIDTH - 70).toFloat(),
            (customerTop + 205).toFloat(),
            36f,
            36f,
            surfacePaint
        )

        val customerPhoto = CustomerPhotoStore(context)
            .file(snapshot.customer.id)
            ?.takeIf { it.isFile }
            ?.let { file ->
                runCatching {
                    BitmapFactory.decodeFile(file.absolutePath)
                }.getOrNull()
            }

        val customerTextX = if (customerPhoto != null) 270 else 104
        val customerTextWidth = WIDTH - customerTextX - 104

        customerPhoto?.let { photo ->
            val photoBounds = RectF(
                102f,
                (customerTop + 44).toFloat(),
                222f,
                (customerTop + 164).toFloat()
            )
            val clip = Path().apply {
                addOval(photoBounds, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(
                photo,
                null,
                photoBounds,
                Paint(Paint.ANTI_ALIAS_FLAG)
            )
            canvas.restore()

            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(218, 226, 222)
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            canvas.drawOval(photoBounds, border)
            photo.recycle()
        }

        painter.text(
            value = "بيانات الزبون",
            x = customerTextX,
            y = customerTop + 24,
            width = customerTextWidth,
            size = 25f,
            color = gold,
            isBold = true
        )
        painter.text(
            value = snapshot.customer.name,
            x = customerTextX,
            y = customerTop + 67,
            width = customerTextWidth,
            size = 40f,
            color = ink,
            isBold = true
        )

        val contactLine = buildList {
            snapshot.customer.phone
                ?.takeIf { it.isNotBlank() }
                ?.let { add("الهاتف: " + it) }
            snapshot.customer.area
                .takeIf { it.isNotBlank() }
                ?.let { add("المنطقة: " + it) }
        }.joinToString("    •    ")

        if (contactLine.isNotBlank()) {
            painter.text(
                value = contactLine,
                x = customerTextX,
                y = customerTop + 135,
                width = customerTextWidth,
                size = 28f,
                color = muted
            )
        }

        val balanceTop = 648
        val balanceFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (snapshot.currentBalance > 0L) redSoft else greenSoft
        }
        canvas.drawRoundRect(
            70f,
            balanceTop.toFloat(),
            (WIDTH - 70).toFloat(),
            (balanceTop + 310).toFloat(),
            46f,
            46f,
            balanceFill
        )

        val balanceBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = if (snapshot.currentBalance > 0L) {
                Color.rgb(235, 176, 169)
            } else {
                Color.rgb(157, 215, 191)
            }
        }
        canvas.drawRoundRect(
            70f,
            balanceTop.toFloat(),
            (WIDTH - 70).toFloat(),
            (balanceTop + 310).toFloat(),
            46f,
            46f,
            balanceBorder
        )

        painter.text(
            value = "الدين الحالي",
            x = 100,
            y = balanceTop + 42,
            width = WIDTH - 200,
            size = 32f,
            color = muted,
            isBold = true,
            alignment = Layout.Alignment.ALIGN_CENTER
        )
        painter.text(
            value = formatMoney(snapshot.currentBalance),
            x = 100,
            y = balanceTop + 106,
            width = WIDTH - 200,
            size = 80f,
            color = if (snapshot.currentBalance > 0L) red else green,
            isBold = true,
            alignment = Layout.Alignment.ALIGN_CENTER
        )
        painter.text(
            value = if (snapshot.currentBalance > 0L) {
                "حساب مفتوح"
            } else {
                "الحساب مسدد بالكامل"
            },
            x = 100,
            y = balanceTop + 233,
            width = WIDTH - 200,
            size = 29f,
            color = if (snapshot.currentBalance > 0L) red else green,
            isBold = true,
            alignment = Layout.Alignment.ALIGN_CENTER
        )

        var y = 998
        painter.text(
            value = if (snapshot.currentBalance > 0L) {
                "الحركات التي تكوّن الرصيد الحالي"
            } else {
                "حالة الحساب"
            },
            x = 76,
            y = y,
            width = WIDTH - 152,
            size = 34f,
            color = ink,
            isBold = true
        )
        y += 62

        val currentCycle = currentCycleEntries(snapshot).take(6)

        when {
            snapshot.currentBalance <= 0L -> {
                val paidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = greenSoft
                }
                canvas.drawRoundRect(
                    70f,
                    y.toFloat(),
                    (WIDTH - 70).toFloat(),
                    (y + 126).toFloat(),
                    30f,
                    30f,
                    paidPaint
                )
                painter.text(
                    value = "لا يوجد دين حالي — الحساب مسدد بالكامل",
                    x = 100,
                    y = y + 38,
                    width = WIDTH - 200,
                    size = 31f,
                    color = greenDark,
                    isBold = true,
                    alignment = Layout.Alignment.ALIGN_CENTER
                )
                y += 150
            }

            currentCycle.isEmpty() -> {
                canvas.drawRoundRect(
                    70f,
                    y.toFloat(),
                    (WIDTH - 70).toFloat(),
                    (y + 118).toFloat(),
                    30f,
                    30f,
                    surfacePaint
                )
                painter.text(
                    value = "الرصيد الحالي ناتج عن الدين السابق المسجل للزبون.",
                    x = 102,
                    y = y + 35,
                    width = WIDTH - 204,
                    size = 29f,
                    color = muted,
                    isBold = true,
                    alignment = Layout.Alignment.ALIGN_CENTER
                )
                y += 142
            }

            else -> {
                currentCycle.forEach { entry ->
                    painter.movement(entry = entry, y = y)
                    y += 92
                }
            }
        }

        val footerTop = maxOf(y + 22, HEIGHT - 122)
        canvas.drawRoundRect(
            70f,
            footerTop.toFloat(),
            (WIDTH - 70).toFloat(),
            (HEIGHT - 38).toFloat(),
            28f,
            28f,
            surfacePaint
        )
        painter.text(
            value = "شكرًا لحسن تعاملكم 🌹",
            x = 100,
            y = footerTop + 22,
            width = WIDTH - 200,
            size = 28f,
            color = greenDark,
            isBold = true,
            alignment = Layout.Alignment.ALIGN_CENTER
        )
        painter.text(
            value = "صيدلية رعد",
            x = 100,
            y = footerTop + 61,
            width = WIDTH - 200,
            size = 21f,
            color = muted,
            alignment = Layout.Alignment.ALIGN_CENTER
        )

        return bitmap
    }

    private class StatementPainter(
        private val canvas: Canvas,
        private val regular: Typeface,
        private val bold: Typeface
    ) {
        fun text(
            value: String,
            x: Int,
            y: Int,
            width: Int,
            size: Float,
            color: Int,
            isBold: Boolean = false,
            alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
            maxLines: Int = 2
        ) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = if (isBold) bold else regular
            }

            val layout = StaticLayout.Builder.obtain(
                value,
                0,
                value.length,
                paint,
                width.coerceAtLeast(1)
            )
                .setAlignment(alignment)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .build()

            canvas.save()
            canvas.translate(x.toFloat(), y.toFloat())
            layout.draw(canvas)
            canvas.restore()
        }

        fun movement(
            entry: LedgerEntry,
            y: Int
        ) {
            val isDebt = entry.type == EntryType.DEBT
            val background = if (isDebt) redSoft else greenSoft
            val accent = if (isDebt) red else green

            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = background
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(
                70f,
                y.toFloat(),
                (WIDTH - 70).toFloat(),
                (y + 78).toFloat(),
                24f,
                24f,
                fill
            )

            val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
            }
            canvas.drawRoundRect(
                (WIDTH - 88).toFloat(),
                (y + 14).toFloat(),
                (WIDTH - 78).toFloat(),
                (y + 64).toFloat(),
                5f,
                5f,
                marker
            )

            text(
                value = (if (isDebt) "دين" else "تحصيل") +
                    "  •  " + formatDate(entry.createdAt),
                x = 520,
                y = y + 20,
                width = WIDTH - 630,
                size = 27f,
                color = ink,
                isBold = true
            )
            text(
                value = (if (isDebt) "+" else "-") + formatMoney(entry.amount),
                x = 92,
                y = y + 18,
                width = 390,
                size = 31f,
                color = accent,
                isBold = true,
                alignment = Layout.Alignment.ALIGN_CENTER
            )
        }
    }

    private fun shareDir(context: Context): File =
        File(context.cacheDir, "shared").apply { mkdirs() }

    private fun fileName(
        snapshot: StatementSnapshot,
        extension: String
    ): String =
        "statement-" +
            safeName(snapshot.customer.name) +
            "-" +
            snapshot.generatedAt +
            "." +
            extension

    private fun safeName(value: String): String =
        value.trim()
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "-")
            .trim('-')
            .ifBlank { "customer" }
}

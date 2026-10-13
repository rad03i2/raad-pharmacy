package com.radwan.raadpharmacy.data

import android.content.Context

/**
 * User-editable WhatsApp message templates. The stored templates never contain real customer
 * information; placeholders are resolved only at share time.
 */
object ReadyMessages {
    enum class Kind(val title: String, val defaultText: String) {
        NORMAL("كشف حساب عادي",
            "السلام عليكم\nنرفق لكم كشف حسابكم لدى صيدلية رعد.\nالزبون: {الزبون}\nالمبلغ المتبقي: {المبلغ} ({المبلغ_كتابة}).\nشكرًا لتعاونكم."),
        ONE_DAY("تذكير يوم",
            "السلام عليكم\nانقضى موعد التسديد المتفق عليه بعد يوم واحد.\nالزبون: {الزبون}\nالمبلغ المتبقي: {المبلغ} ({المبلغ_كتابة}).\nمرفق كشف الحساب."),
        TWO_DAYS("تذكير يومين",
            "السلام عليكم\nانقضى موعد التسديد المتفق عليه بعد يومين.\nالزبون: {الزبون}\nالمبلغ المتبقي: {المبلغ} ({المبلغ_كتابة}).\nمرفق كشف الحساب."),
        ONE_WEEK("تذكير أسبوع",
            "السلام عليكم\nانقضى موعد التسديد المتفق عليه بعد أسبوع.\nالزبون: {الزبون}\nالمبلغ المتبقي: {المبلغ} ({المبلغ_كتابة}).\nمرفق كشف الحساب."),
        TWO_WEEKS("تذكير أسبوعين",
            "السلام عليكم\nانقضى موعد التسديد المتفق عليه بعد أسبوعين.\nالزبون: {الزبون}\nالمبلغ المتبقي: {المبلغ} ({المبلغ_كتابة}).\nمرفق كشف الحساب."),
        THIRTY_DAYS("متابعة 30 يومًا",
            "السلام عليكم\nنذكركم بوجود مبلغ مستحق منذ {الأيام} يومًا.\nالزبون: {الزبون}\nالمبلغ المتبقي: {المبلغ} ({المبلغ_كتابة}).\nمرفق كشف الحساب."),
        SIXTY_DAYS("متابعة 60 يومًا فأكثر",
            "السلام عليكم\nنرجو مراجعة حسابكم المتأخر منذ {الأيام} يومًا.\nالزبون: {الزبون}\nالمبلغ المتبقي: {المبلغ} ({المبلغ_كتابة}).\nمرفق كشف الحساب.")
    }

    private const val PREFS = "raad_ready_messages_v1"
    fun load(context: Context, kind: Kind): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(kind.name, null)?.takeIf { it.isNotBlank() } ?: kind.defaultText

    fun save(context: Context, kind: Kind, text: String) {
        require(text.isNotBlank() && text.length <= 2000)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(kind.name, text).apply()
    }

    fun render(
        template: String,
        customerName: String,
        amount: Long,
        ageDays: Long? = null
    ): String {
        val displayAmount = "%,d دينار عراقي".format(java.util.Locale.US, amount.coerceAtLeast(0L))
        val hasAmount = "{المبلغ}" in template
        val hasAmountWords = "{المبلغ_كتابة}" in template
        val rendered = template
            .replace("{الزبون}", customerName)
            .replace("{المبلغ}", displayAmount)
            .replace("{المبلغ_كتابة}", IraqiAmountWords.format(amount))
            .replace("{الأيام}", ageDays?.toString() ?: "—")
        // The financial amount remains present even if the editor removes its placeholders.
        return if (hasAmount && hasAmountWords) rendered else rendered.trimEnd() +
            "\nالمبلغ المتبقي: $displayAmount (" + IraqiAmountWords.format(amount) + ")."
    }
}

/** Conservative Arabic integer-money formatter (Iraqi dinars, no decimal part). */
object IraqiAmountWords {
    private val units = arrayOf("صفر", "واحد", "اثنان", "ثلاثة", "أربعة", "خمسة",
        "ستة", "سبعة", "ثمانية", "تسعة", "عشرة", "أحد عشر", "اثنا عشر",
        "ثلاثة عشر", "أربعة عشر", "خمسة عشر", "ستة عشر", "سبعة عشر",
        "ثمانية عشر", "تسعة عشر")
    private val tens = arrayOf("", "", "عشرون", "ثلاثون", "أربعون", "خمسون",
        "ستون", "سبعون", "ثمانون", "تسعون")
    private val hundreds = arrayOf("", "مئة", "مئتان", "ثلاثمئة", "أربعمئة",
        "خمسمئة", "ستمئة", "سبعمئة", "ثمانمئة", "تسعمئة")

    private fun underThousand(n: Int): String {
        val result = mutableListOf<String>()
        if (n >= 100) result += hundreds[n / 100]
        val rest = n % 100
        if (rest in 1..19) result += units[rest]
        else if (rest >= 20) {
            if (rest % 10 != 0) result += units[rest % 10]
            result += tens[rest / 10]
        }
        return result.joinToString(" و")
    }

    fun format(amount: Long): String {
        val n = amount.coerceAtLeast(0L)
        if (n == 0L) return "صفر دينار عراقي"
        val groups = arrayOf(
            arrayOf("", "", ""),
            arrayOf("ألف", "ألفان", "آلاف"),
            arrayOf("مليون", "مليونان", "ملايين"),
            arrayOf("مليار", "ملياران", "مليارات"),
            arrayOf("تريليون", "تريليونان", "تريليونات")
        )
        var remaining = n
        val parts = mutableListOf<String>()
        var index = 0
        while (remaining > 0L && index < groups.size) {
            val v = (remaining % 1000).toInt()
            if (v != 0) {
                val group = if (index == 0) underThousand(v) else when {
                    v == 1 -> groups[index][0]
                    v == 2 -> groups[index][1]
                    v in 3..10 -> underThousand(v) + " " + groups[index][2]
                    else -> underThousand(v) + " " + groups[index][0]
                }
                parts.add(0, group)
            }
            remaining /= 1000
            index++
        }
        return parts.joinToString(" و") + " دينار عراقي"
    }
}

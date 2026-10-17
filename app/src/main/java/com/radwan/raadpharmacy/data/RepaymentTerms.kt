package com.radwan.raadpharmacy.data

/**
 * Compatible storage for an optional repayment agreement.
 *
 * The ledger and existing Supabase transactions schema synchronize the notes field.
 * Encoding the agreement as a strictly delimited suffix retains compatibility with
 * existing Room, backups, outbox and cloud transactions without destructive migrations.
 * All 3.3.21 UI reads decoded LedgerEntry.details, not the internal suffix.
 */
object RepaymentTerms {
    val ordinaryChoices = listOf(1, 2, 7, 14, 21, 28, 60)
    // Negative values encode minutes; positive values retain the original days protocol.
    val shortChoices = listOf(-5, -30, -60, -120)
    val choices = ordinaryChoices + shortChoices
    fun visibleChoices(shortEnabled: Boolean): List<Int?> = listOf(null) + ordinaryChoices + if (shortEnabled) shortChoices else emptyList()
    fun shortDueAt(entry: LedgerEntry): Long? = if (entry.type == EntryType.DEBT && entry.repaymentDays in shortChoices && entry.createdAt > 0L)
        Math.addExact(entry.createdAt, -entry.repaymentDays!!.toLong() * 60_000L) else null
    private val suffix = Regex("\\n\\[\\[RAAD_REPAY_V1:(1|2|7|14|21|28|60|-5|-30|-60|-120)\\]\\]$")

    fun unpack(stored: String): Pair<String, Int?> {
        val match = suffix.find(stored) ?: return stored to null
        return stored.substring(0, match.range.first) to match.groupValues[1].toInt()
    }

    fun pack(details: String, days: Int?): String {
        require(days == null || days in choices) { "Unsupported repayment reminder" }
        val cleaned = unpack(details).first
        return if (days == null) cleaned else cleaned + 10.toChar() + "[[RAAD_REPAY_V1:$days]]"
    }

    fun label(days: Int): String = when (days) {
        1 -> "يوم"
        2 -> "يومان"
        7 -> "أسبوع"
        14 -> "أسبوعان"
        21 -> "ثلاثة أسابيع"
        28 -> "أربعة أسابيع"
        60 -> "60 يومًا"
        -5 -> "5 دقائق"
        -30 -> "نصف ساعة"
        -60 -> "ساعة"
        -120 -> "ساعتان"
        else -> "30 يومًا"
    }
}

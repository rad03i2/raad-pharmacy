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
    val choices = listOf(1, 2, 7, 14)
    private val suffix = Regex("\\n\\[\\[RAAD_REPAY_V1:(1|2|7|14)\\]\\]$")

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
        else -> "30 يومًا"
    }
}

package com.radwan.raadpharmacy.data

import java.time.LocalDate

/**
 * Isolated, in-memory DEMO fixture for exercising the follow-up center.
 * Never insert these customers into Room or Supabase; never assign real
 * telephone numbers or send statements to contacts for demonstration data.
 */
object FollowupDemoData {
    const val PREFIX = "demo-followup-v3161-"
    private val ages = listOf(
        30L, 31L, 34L, 37L, 42L, 45L, 48L, 59L,
        60L, 60L, 60L, 60L,
        61L, 65L, 72L, 79L, 90L, 100L, 125L, 180L
    )
    private val names = listOf(
        "أحمد محمود", "محمد علي", "عمر خالد", "حسين جاسم", "مصطفى سالم",
        "علي عباس", "قاسم ناصر", "ياسر محمد", "سامر حسن", "خالد حميد",
        "سيف رائد", "وليد عبد الله", "كريم أحمد", "مروان إبراهيم",
        "فراس طارق", "مازن حسن", "نزار جميل", "حيدر فاضل",
        "باسم داود", "زيد محمود"
    )

    data class Fixture(val customers: List<Customer>, val movements: List<LedgerEntry>)

    /** Dates are relative to the supplied Iraqi local day, so ageing always stays realistic. */
    fun create(today: LocalDate = LocalDate.now(DebtFollowupEngine.IRAQ_ZONE)): Fixture {
        val customers = ArrayList<Customer>(20)
        val movements = ArrayList<LedgerEntry>(52)
        fun instant(days: Long): Long =
            today.minusDays(days).atStartOfDay(DebtFollowupEngine.IRAQ_ZONE).toInstant().toEpochMilli()

        ages.forEachIndexed { i, age ->
            val n = i + 1
            val id = PREFIX + n.toString().padStart(2, '0')
            customers += Customer(
                id = id,
                name = "تجريبي ${n.toString().padStart(2, '0')} — ${names[i]}",
                phone = null, // Prevent accidental contact of a real recipient.
                area = "حساب افتراضي",
                notes = "بيانات اختبار مؤقتة — غير محفوظة ولا تُزامَن",
                openingDebt = 0L,
                createdAt = instant(age + 25L)
            )
            val oldDebt = 15_000L + (i * 5_000L)
            movements += LedgerEntry(
                id = "${id}-debt-old",
                customerId = id,
                type = EntryType.DEBT,
                amount = oldDebt,
                details = "دين تجريبي قديم",
                createdAt = instant(age)
            )
            // Some accounts also have recent debts, verifying that the
            // overdue amount is not simply the customer's entire balance.
            if (i % 3 == 0) {
                movements += LedgerEntry(
                    id = "${id}-debt-recent",
                    customerId = id,
                    type = EntryType.DEBT,
                    amount = 6_000L + i * 1_000L,
                    details = "دين تجريبي حديث",
                    createdAt = instant(5)
                )
            }
            // Partial settlements retain a nonzero portion of the aged debt.
            if (i % 2 == 0) {
                movements += LedgerEntry(
                    id = "${id}-partial-payment",
                    customerId = id,
                    type = EntryType.PAYMENT,
                    amount = oldDebt / 4,
                    details = "تحصيل جزئي تجريبي",
                    createdAt = instant(10)
                )
            }
        }
        return Fixture(customers, movements)
    }

    fun isDemoCustomer(customerId: String): Boolean = customerId.startsWith(PREFIX)
}

package com.radwan.raadpharmacy.data

data class ReportPeriodMetrics(
    val debts: Long,
    val collections: Long,
    val bottles: Long,
    val newCustomers: Int
)

data class CurrentDebtSummary(
    val totalDebt: Long,
    val openAccounts: Int,
    val totalCustomers: Int
)

data class AreaDebtSummary(
    val area: String,
    val balance: Long
)

data class CustomerDebtSummary(
    val customerId: String,
    val name: String,
    val area: String,
    val balance: Long
)

data class DailyMovementSummary(
    val dayKey: String,
    val debts: Long,
    val collections: Long
)

enum class ReportPeriodV11(val label: String) {
    TODAY("اليوم"),
    WEEK("الأسبوع"),
    MONTH("الشهر")
}

data class AdvancedReportSnapshot(
    val period: ReportPeriodV11 = ReportPeriodV11.MONTH,
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    val debts: Long = 0L,
    val collections: Long = 0L,
    val bottles: Long = 0L,
    val newCustomers: Int = 0,
    val totalDebt: Long = 0L,
    val openAccounts: Int = 0,
    val totalCustomers: Int = 0,
    val topAreas: List<AreaDebtSummary> = emptyList(),
    val topCustomers: List<CustomerDebtSummary> = emptyList(),
    val dailyMovement: List<DailyMovementSummary> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null
) {
    val netMovement: Long get() = debts - collections
    val averageOpenDebt: Long
        get() = if (openAccounts > 0) totalDebt / openAccounts else 0L
    val collectionRatePercent: Double
        get() = if (debts > 0L) collections.toDouble() / debts.toDouble() * 100.0
        else if (collections > 0L) 100.0 else 0.0
}

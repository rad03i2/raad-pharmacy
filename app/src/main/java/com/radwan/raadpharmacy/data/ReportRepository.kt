package com.radwan.raadpharmacy.data

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class ReportRepository(context: Context) {
    private val dao = PharmacyLedgerDatabase.get(context.applicationContext).dao()
    private val zone = ZoneId.systemDefault()
    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    suspend fun load(period: ReportPeriodV11): AdvancedReportSnapshot = coroutineScope {
        val (start, end) = boundaries(period)

        val metricsDeferred = async { dao.reportPeriodMetrics(start, end) }
        val currentDeferred = async { dao.currentDebtSummary() }
        val areasDeferred = async { dao.topAreasByDebt(5) }
        val customersDeferred = async { dao.topCustomersByDebt(5) }
        val trendDeferred = async { dao.dailyMovementSummary(start, end) }

        val metrics = metricsDeferred.await()
        val current = currentDeferred.await()
        val rawTrend = trendDeferred.await()

        AdvancedReportSnapshot(
            period = period,
            startMillis = start,
            endMillis = end,
            debts = metrics.debts,
            collections = metrics.collections,
            bottles = metrics.bottles,
            newCustomers = metrics.newCustomers,
            totalDebt = current.totalDebt,
            openAccounts = current.openAccounts,
            totalCustomers = current.totalCustomers,
            topAreas = areasDeferred.await(),
            topCustomers = customersDeferred.await(),
            dailyMovement = fillMissingDays(period, start, rawTrend),
            loading = false
        )
    }

    private fun boundaries(period: ReportPeriodV11): Pair<Long, Long> {
        val today = LocalDate.now(zone)
        val startDate = when (period) {
            ReportPeriodV11.TODAY -> today
            ReportPeriodV11.WEEK -> today.minusDays(6)
            ReportPeriodV11.MONTH -> today.minusDays(29)
        }

        val start = startDate
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val end = today
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

        return start to end
    }

    private fun fillMissingDays(
        period: ReportPeriodV11,
        startMillis: Long,
        raw: List<DailyMovementSummary>
    ): List<DailyMovementSummary> {
        val byDay = raw.associateBy { it.dayKey }
        val startDate = java.time.Instant.ofEpochMilli(startMillis)
            .atZone(zone)
            .toLocalDate()
        val count = when (period) {
            ReportPeriodV11.TODAY -> 1
            ReportPeriodV11.WEEK -> 7
            ReportPeriodV11.MONTH -> 30
        }

        return List(count) { offset ->
            val day = startDate.plusDays(offset.toLong()).format(dayFormatter)
            byDay[day] ?: DailyMovementSummary(day, 0L, 0L)
        }
    }
}

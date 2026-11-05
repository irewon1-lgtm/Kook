package com.krstock.v3.data.price

import java.time.LocalDate
import java.time.DayOfWeek
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** Calendar buckets, independent from investment scoring and source I/O. */
object PriceHistoryAggregation {
    enum class Interval(val label: String) { DAY("일봉"), WEEK("주봉"), MONTH("월봉") }

    data class Candle(
        val firstTradingDate: LocalDate,
        val lastTradingDate: LocalDate,
        val periodStart: LocalDate,
        val periodEnd: LocalDate,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long?,
        val tradingDays: Int,
    )

    fun aggregate(daily: List<PriceHistoryRepository.DailyBar>, interval: Interval): List<Candle> {
        val unique = linkedMapOf<LocalDate, PriceHistoryRepository.DailyBar>()
        daily.forEach { bar ->
            if (bar.date.dayOfWeek == DayOfWeek.SATURDAY || bar.date.dayOfWeek == DayOfWeek.SUNDAY) return@forEach
            require(unique[bar.date]?.let { it == bar } != false) { "Conflicting price date ${bar.date}" }
            unique[bar.date] = bar
        }
        val groups = unique.values.sortedBy { it.date }.groupBy {
            when (interval) {
                Interval.DAY -> it.date
                Interval.WEEK -> it.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                Interval.MONTH -> it.date.withDayOfMonth(1)
            }
        }
        return groups.map { (start, bars) ->
            val end = when (interval) {
                Interval.DAY -> start
                Interval.WEEK -> start.plusDays(4)
                Interval.MONTH -> YearMonth.from(start).atEndOfMonth()
            }
            val volume = if (bars.any { it.volume == null || it.volume < 0 }) null else runCatching {
                bars.fold(0L) { total, bar -> Math.addExact(total, requireNotNull(bar.volume)) }
            }.getOrNull()
            Candle(
                bars.first().date, bars.last().date, start, end,
                bars.first().open, bars.maxOf { it.high }, bars.minOf { it.low },
                bars.last().close, volume, bars.size,
            )
        }
    }

    fun visible(daily: List<PriceHistoryRepository.DailyBar>, interval: Interval, months: Int): List<Candle> {
        require(months in setOf(1, 3, 6, 12)) { "Unsupported chart window" }
        val all = aggregate(daily, interval)
        val from = all.lastOrNull()?.lastTradingDate?.minusMonths(months.toLong()) ?: return emptyList()
        return all.filter { !it.lastTradingDate.isBefore(from) }
    }
}

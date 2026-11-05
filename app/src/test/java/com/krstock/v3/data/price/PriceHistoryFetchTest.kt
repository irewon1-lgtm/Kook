package com.krstock.v3.data.price

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.LocalDate
import com.krstock.v3.data.price.PriceHistoryAggregation.Interval

class PriceHistoryFetchTest {
    private val cutoff = LocalDate.parse("2026-11-04")
    private fun page(number: Int) = File("src/androidTest/assets/prices_005930_20261105/page$number.json").readText()

    @Test fun realSourcePaginationUsesSupportedSizeAndCoversWholeFirstCalendarBucket() {
        val received = runCatching {
            PriceHistoryRepository.fetchBars(cutoff) { number, size ->
                if (size > 60) throw IOException("Recorded provider HTTP400: unsupported pageSize")
                page(number)
            }
        }
        assertTrue("Real source pagination must succeed: ${received.exceptionOrNull()}", received.isSuccess)
        val bars = received.getOrThrow()
        assertEquals(299, bars.size)
        assertEquals(LocalDate.parse("2025-08-12"), bars.first().date)
        assertEquals(cutoff, bars.last().date)
    }

    @Test fun realSamsungWeekAndMonthMatchIndependentRecordedOhlcvTotals() {
        val bars = (1..5).flatMap { PriceHistoryRepository.parseBars(page(it), cutoff) }
        val week = PriceHistoryAggregation.aggregate(bars,Interval.WEEK).first { it.periodStart.toString()=="2026-10-26" }
        assertEquals(5, week.tradingDays)
        assertEquals(213500.0, week.open, 0.0)
        assertEquals(249000.0, week.high, 0.0)
        assertEquals(211500.0, week.low, 0.0)
        assertEquals(220500.0, week.close, 0.0)
        assertEquals(88057100L, week.volume)
        val month = PriceHistoryAggregation.aggregate(bars,Interval.MONTH).first { it.periodStart.toString()=="2026-10-01" }
        assertEquals(20, month.tradingDays)
        assertEquals(266500.0, month.open, 0.0)
        assertEquals(279500.0, month.high, 0.0)
        assertEquals(203500.0, month.low, 0.0)
        assertEquals(220500.0, month.close, 0.0)
        assertEquals(361741542L, month.volume)
        val crossYear = PriceHistoryAggregation.aggregate(bars,Interval.WEEK).first { it.periodStart.toString()=="2025-12-29" }
        assertEquals(3, crossYear.tradingDays)
        assertEquals(119400.0, crossYear.open, 0.0)
        assertEquals(128500.0, crossYear.high, 0.0)
        assertEquals(118200.0, crossYear.low, 0.0)
        assertEquals(128500.0, crossYear.close, 0.0)
        assertEquals(69885632L, crossYear.volume)
    }

    @Test fun currentWeekAndMonthContainOnlyReportedDaysThroughVerifiedCutoff() {
        val bars = (1..5).flatMap { PriceHistoryRepository.parseBars(page(it),cutoff) }
        for (interval in listOf(Interval.WEEK,Interval.MONTH)) {
            val last = PriceHistoryAggregation.aggregate(bars,interval).last()
            assertEquals(cutoff, last.lastTradingDate)
            assertEquals(3, last.tradingDays)
            assertEquals(219000.0, last.open, 0.0)
            assertEquals(232000.0, last.high, 0.0)
            assertEquals(208000.0, last.low, 0.0)
            assertEquals(221500.0, last.close, 0.0)
            assertEquals(50178389L, last.volume)
        }
    }

    @Test fun singleReportedDayForNewListingIsKeptWithoutInventingMoreHistory() {
        val result = runCatching {
            PriceHistoryRepository.fetchBars(cutoff) { _, _ -> """[{"localTradedAt":"2026-11-04","openPrice":"100","highPrice":"105","lowPrice":"95","closePrice":"101","accumulatedTradingVolume":"1000"}]""" }
        }
        assertTrue(result.isSuccess)
        assertEquals(1,result.getOrThrow().size)
    }
}

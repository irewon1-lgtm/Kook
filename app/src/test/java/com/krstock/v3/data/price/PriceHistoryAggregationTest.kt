package com.krstock.v3.data.price

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import com.krstock.v3.data.price.PriceHistoryAggregation.Interval

class PriceHistoryAggregationTest {
    private fun bar(day: String, o: Double, h: Double, l: Double, c: Double, v: Long? = 10L) =
        PriceHistoryRepository.DailyBar(LocalDate.parse(day), o, h, l, c, v)

    private fun boundaryBars() = listOf(
        bar("2025-12-29",100.0,110.0,95.0,105.0,10),
        bar("2025-12-30",105.0,112.0,101.0,108.0,20),
        bar("2026-01-02",110.0,120.0,104.0,115.0,30),
        bar("2026-01-05",120.0,130.0,118.0,125.0,40),
    )

    @Test fun mondayThroughFridayWeekRemainsOneBucketAcrossNewYearAndHolidayGaps() {
        val result = PriceHistoryAggregation.aggregate(boundaryBars().reversed(), Interval.WEEK)
        assertEquals(2, result.size)
        val first = result.first()
        assertEquals(LocalDate.parse("2025-12-29"), first.periodStart)
        assertEquals(LocalDate.parse("2026-01-02"), first.periodEnd)
        assertEquals(LocalDate.parse("2025-12-29"), first.firstTradingDate)
        assertEquals(LocalDate.parse("2026-01-02"), first.lastTradingDate)
        assertEquals(3, first.tradingDays)
        assertEquals(100.0, first.open, 0.0)
        assertEquals(120.0, first.high, 0.0)
        assertEquals(95.0, first.low, 0.0)
        assertEquals(115.0, first.close, 0.0)
        assertEquals(60L, first.volume)
    }

    @Test fun calendarMonthUsesFirstOpenLastCloseAndExactVolumeSum() {
        val result = PriceHistoryAggregation.aggregate(boundaryBars(), Interval.MONTH)
        assertEquals(2, result.size)
        val dec = result.first()
        assertEquals(LocalDate.parse("2025-12-01"), dec.periodStart)
        assertEquals(LocalDate.parse("2025-12-31"), dec.periodEnd)
        assertEquals(100.0, dec.open, 0.0)
        assertEquals(112.0, dec.high, 0.0)
        assertEquals(95.0, dec.low, 0.0)
        assertEquals(108.0, dec.close, 0.0)
        assertEquals(30L, dec.volume)
        assertEquals(110.0, result.last().open, 0.0)
        assertEquals(125.0, result.last().close, 0.0)
        assertEquals(70L, result.last().volume)
    }

    @Test fun weekendRowsAndEmptyWeeksDoNotCreateTradingDays() {
        val input = boundaryBars() + bar("2026-01-03",900.0,1000.0,800.0,950.0) + bar("2026-01-19",130.0,140.0,125.0,135.0)
        val result = PriceHistoryAggregation.aggregate(input, Interval.WEEK)
        assertEquals(3, result.size)
        assertEquals(120.0, result.first().high, 0.0)
        assertEquals(3, result.first().tradingDays)
        assertEquals(LocalDate.parse("2026-01-19"), result.last().periodStart)
    }

    @Test fun monthlyGroupingDoesNotMergeSameMonthInDifferentYears() {
        val result = PriceHistoryAggregation.aggregate(listOf(
            bar("2025-01-02",10.0,12.0,9.0,11.0),
            bar("2026-01-02",20.0,22.0,19.0,21.0),
        ), Interval.MONTH)
        assertEquals(2, result.size)
    }

    @Test fun leapFebruaryEndsOnTheTwentyNinthAndNeverIncludesMarch() {
        val result = PriceHistoryAggregation.aggregate(listOf(
            bar("2024-02-28",100.0,110.0,95.0,105.0),
            bar("2024-02-29",106.0,120.0,100.0,115.0),
            bar("2024-03-04",116.0,130.0,110.0,125.0),
        ),Interval.MONTH)
        assertEquals(2,result.size)
        assertEquals(LocalDate.parse("2024-02-29"),result.first().periodEnd)
        assertEquals(2,result.first().tradingDays)
        assertEquals(115.0,result.first().close,0.0)
        assertEquals(20L,result.first().volume)
    }

    @Test fun missingVolumeIsUnavailableRatherThanAnInventedPartialSum() {
        val result = PriceHistoryAggregation.aggregate(listOf(
            bar("2026-11-02",100.0,105.0,98.0,102.0,10),
            bar("2026-11-04",103.0,110.0,100.0,108.0,null),
        ), Interval.WEEK).single()
        assertNull(result.volume)
        assertEquals(2, result.tradingDays)
        assertEquals(108.0, result.close, 0.0)
    }

    @Test fun overflowingVolumeRemainsUnavailableAndIsNeverWrapped() {
        val result = PriceHistoryAggregation.aggregate(listOf(
            bar("2026-11-02",100.0,105.0,98.0,102.0,Long.MAX_VALUE),
            bar("2026-11-03",103.0,110.0,100.0,108.0,1),
        ), Interval.WEEK).single()
        assertNull(result.volume)
    }

    @Test fun rangeSelectionDoesNotChangeTheOpeningPriceOfAWeeklyOrMonthlyCandle() {
        val input = listOf(
            bar("2026-09-07",100.0,110.0,95.0,105.0),
            bar("2026-09-09",110.0,120.0,100.0,115.0),
            bar("2026-09-11",120.0,130.0,110.0,125.0),
            bar("2026-10-09",130.0,140.0,120.0,135.0),
        )
        for (interval in listOf(Interval.WEEK,Interval.MONTH)) {
            val first = PriceHistoryAggregation.visible(input, interval, 1).first()
            assertEquals(LocalDate.parse("2026-09-07"), first.firstTradingDate)
            assertEquals(100.0, first.open, 0.0)
        }
    }

    @Test fun singleTradingDayAndEmptyHistoryDoNotGenerateExtraBars() {
        for (interval in Interval.entries) {
            assertTrue(PriceHistoryAggregation.aggregate(emptyList(), interval).isEmpty())
            val one = PriceHistoryAggregation.aggregate(listOf(bar("2026-11-04",100.0,105.0,95.0,102.0,0)),interval).single()
            assertEquals(1, one.tradingDays)
            assertEquals(0L, one.volume)
            assertEquals(LocalDate.parse("2026-11-04"), one.lastTradingDate)
        }
    }

    @Test fun duplicateDatesNeverDoubleVolumeAndConflictingDatesAreRejected() {
        val first = boundaryBars().first()
        assertEquals(10L, PriceHistoryAggregation.aggregate(listOf(first,first),Interval.WEEK).single().volume)
        try {
            PriceHistoryAggregation.aggregate(listOf(first,first.copy(close=106.0)),Interval.WEEK)
            fail("Conflicting same-day prices must not become a candle")
        } catch (_: IllegalArgumentException) { }
    }
}

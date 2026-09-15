package com.krstock.v3.data.analysis

import com.krstock.v3.data.model.IntegratedAnalysis
import com.krstock.v3.data.model.QuarterlyHistory
import com.krstock.v3.data.model.QuarterlyPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarterlyTrendAnalyzerTest {
    private fun point(
        year: Int,
        quarter: Int,
        revenue: Double?,
        opIncome: Double? = revenue?.times(0.1),
        margin: Double? = if (revenue != null && opIncome != null && revenue != 0.0) opIncome / revenue * 100.0 else null,
        yoy: Double? = null,
        scope: String = "CFS",
        basis: String = "DIRECT_3M",
        reason: String? = null
    ) = QuarterlyPoint(
        period = "${year}Q$quarter",
        fiscalYear = year,
        quarter = quarter,
        revenue = revenue,
        operatingIncome = opIncome,
        operatingMargin = margin,
        revenueYoY = yoy,
        revenueQoQ = null,
        scope = scope,
        basis = basis,
        reason = reason
    )

    @Test
    fun eightConsecutiveQuartersProduceAggregateTrendAccelerationAndQuality() {
        val revenues = listOf(100.0, 105.0, 110.0, 115.0, 125.0, 135.0, 150.0, 170.0)
        val margins = listOf(8.0, 8.2, 8.5, 8.8, 9.0, 9.5, 10.5, 11.0)
        val yoy = listOf(null, null, null, null, 25.0, 28.6, 36.4, 47.8)
        val points = revenues.indices.map { i ->
            val year = if (i < 4) 2025 else 2026
            val q = i % 4 + 1
            point(
                year = year,
                quarter = q,
                revenue = revenues[i],
                opIncome = revenues[i] * margins[i] / 100.0,
                margin = margins[i],
                yoy = yoy[i],
                basis = if (i == 7) "FY_MINUS_Q3" else "DIRECT_3M"
            )
        }
        val history = QuarterlyHistory("005930", points = points, loaded = true, source = "test")
        val text = QuarterlyTrendAnalyzer.summarize(history)

        assertEquals(8, history.availableQuarterCount)
        assertEquals(7, history.directQuarterCount)
        assertEquals(1, history.reconstructedQuarterCount)
        assertTrue(text.contains("연속 실제 분기 8개"))
        assertTrue(text.contains("최근 4개 분기의 합산 매출"))
        assertTrue(text.contains("직전 4개 분기"))
        assertTrue(text.contains("매출가중 영업이익률"))
        assertTrue(text.contains("성장 속도가 가속"))
        assertTrue(text.contains("직접 3개월 값은 7개"))
        assertTrue(text.contains("복원한 값은 1개"))
    }

    @Test
    fun nonContiguousFourObservedQuartersDoNotBecomeFakeTrend() {
        val history = QuarterlyHistory(
            issuerId = "gap",
            loaded = true,
            points = listOf(
                point(2025, 3, 100.0),
                point(2025, 4, 110.0),
                point(2026, 1, 120.0),
                point(2026, 2, null, reason = "MISSING"),
                point(2026, 3, 140.0),
                point(2026, 4, 150.0)
            )
        )
        assertEquals(2, history.availableQuarterCount)
        val text = QuarterlyTrendAnalyzer.summarize(history)
        assertTrue(text.contains("판독을 보류"))
        assertTrue(text.contains("결측 분기를 건너뛰거나"))
    }

    @Test
    fun missingLatestQuarterFailsClosedInsteadOfShiftingLatestBackward() {
        val history = QuarterlyHistory(
            issuerId = "latest-gap",
            loaded = true,
            points = listOf(
                point(2025, 3, 100.0),
                point(2025, 4, 105.0),
                point(2026, 1, 110.0),
                point(2026, 2, 115.0),
                point(2026, 3, null, reason = "MISSING")
            )
        )
        assertTrue(history.hasTrailingGap)
        assertEquals(0, history.availableQuarterCount)
        assertTrue(QuarterlyTrendAnalyzer.summarize(history).contains("최신 대상 분기의 실제 매출이 비어"))
    }

    @Test
    fun cfsOfsScopeChangeStopsComparableChain() {
        val history = QuarterlyHistory(
            issuerId = "scope",
            loaded = true,
            points = listOf(
                point(2025, 1, 100.0, scope = "OFS"),
                point(2025, 2, 101.0, scope = "OFS"),
                point(2025, 3, 102.0, scope = "OFS"),
                point(2025, 4, 103.0, scope = "OFS"),
                point(2026, 1, 104.0, scope = "CFS"),
                point(2026, 2, 105.0, scope = "CFS"),
                point(2026, 3, 106.0, scope = "CFS"),
                point(2026, 4, 107.0, scope = "CFS")
            )
        )
        assertEquals("CFS", history.comparisonScope)
        assertEquals(4, history.availableQuarterCount)
        val enriched = QuarterlyTrendAnalyzer.enrich(
            IntegratedAnalysis("x", "x", "x", "x", "x", "x", emptyList(), "x"),
            history
        )
        assertEquals("4/8 연속분기 확인", enriched.quarterlyCoverage)
        assertTrue(enriched.quarterlyTrend.contains("동일 회계범위(CFS)"))
    }

    @Test
    fun yoyDecelerationIsExplicit() {
        val history = QuarterlyHistory(
            issuerId = "slow",
            loaded = true,
            points = listOf(
                point(2025, 1, 100.0), point(2025, 2, 100.0), point(2025, 3, 100.0), point(2025, 4, 100.0),
                point(2026, 1, 130.0, yoy = 30.0), point(2026, 2, 125.0, yoy = 25.0),
                point(2026, 3, 115.0, yoy = 15.0), point(2026, 4, 108.0, yoy = 8.0)
            )
        )
        val text = QuarterlyTrendAnalyzer.summarize(history)
        assertTrue(text.contains("성장 속도가 둔화"))
    }

    @Test
    fun enrichmentKeepsExistingAnalysisAndAddsQuarterFields() {
        val base = IntegratedAnalysis(
            regimeTitle = "x",
            thesis = "x",
            industryContext = "x",
            combinationMeaning = "x",
            causeInvestigation = "x",
            consequence = "x",
            falsifiers = emptyList(),
            confidenceNote = "x"
        )
        val history = QuarterlyHistory(
            issuerId = "x",
            loaded = true,
            points = (0 until 8).map { i ->
                point(2025 + i / 4, i % 4 + 1, 100.0 + i)
            }
        )
        val enriched = QuarterlyTrendAnalyzer.enrich(base, history)
        assertEquals("8/8 연속분기 확인", enriched.quarterlyCoverage)
        assertTrue(enriched.quarterlyTrend.isNotBlank())
        assertTrue(enriched.quarterlySignal.isNotBlank())
        assertEquals("x", enriched.regimeTitle)
    }
}

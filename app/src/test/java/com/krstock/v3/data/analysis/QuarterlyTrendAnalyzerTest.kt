package com.krstock.v3.data.analysis

import com.krstock.v3.data.model.IntegratedAnalysis
import com.krstock.v3.data.model.QuarterlyHistory
import com.krstock.v3.data.model.QuarterlyPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarterlyTrendAnalyzerTest {
    @Test
    fun eightQuarterHistoryProducesTrendWithoutInventingMissingValues() {
        val history = QuarterlyHistory(
            issuerId = "005930",
            loaded = true,
            source = "test",
            points = (1..8).map { i ->
                val year = if (i <= 4) 2025 else 2026
                val quarter = if (i <= 4) i else i - 4
                QuarterlyPoint(
                    period = "${year}Q$quarter",
                    fiscalYear = year,
                    quarter = quarter,
                    revenue = 100.0 + i * 10.0,
                    operatingIncome = 10.0 + i,
                    operatingMargin = 10.0 + i * 0.5,
                    revenueYoY = if (i > 4) 20.0 else null,
                    revenueQoQ = 5.0,
                    scope = "CFS",
                    basis = "DIRECT_3M"
                )
            }
        )
        val text = QuarterlyTrendAnalyzer.summarize(history)
        assertTrue(text.contains("실제 분기 8개"))
        assertTrue(text.contains("OpenDART"))
        assertTrue(text.contains("2026Q4"))
    }

    @Test
    fun lessThanFourRealQuartersFailsClosed() {
        val history = QuarterlyHistory(
            issuerId = "x",
            loaded = true,
            points = listOf(
                QuarterlyPoint("2026Q1", 2026, 1, 100.0, null, null, null, null, "CFS", "DIRECT_3M"),
                QuarterlyPoint("2026Q2", 2026, 2, null, null, null, null, null, "CFS", "", reason = "MISSING"),
                QuarterlyPoint("2026Q3", 2026, 3, 120.0, null, null, null, null, "CFS", "DIRECT_3M")
            )
        )
        val text = QuarterlyTrendAnalyzer.summarize(history)
        assertTrue(text.contains("판독을 보류"))
        assertTrue(text.contains("추정으로 메우지 않습니다"))
    }

    @Test
    fun cfsOfsScopeChangeDoesNotCountAsComparableEightQuarters() {
        val history = QuarterlyHistory(
            issuerId = "scope",
            loaded = true,
            points = (1..8).map { i ->
                val year = if (i <= 4) 2025 else 2026
                val quarter = if (i <= 4) i else i - 4
                QuarterlyPoint(
                    period = "${year}Q$quarter",
                    fiscalYear = year,
                    quarter = quarter,
                    revenue = 100.0 + i,
                    operatingIncome = 10.0,
                    operatingMargin = 10.0,
                    revenueYoY = 1.0,
                    revenueQoQ = 1.0,
                    scope = if (i <= 4) "OFS" else "CFS",
                    basis = "DIRECT_3M"
                )
            }
        )
        assertEquals("CFS", history.comparisonScope)
        assertEquals(4, history.availableQuarterCount)
        val enriched = QuarterlyTrendAnalyzer.enrich(
            IntegratedAnalysis("x", "x", "x", "x", "x", "x", emptyList(), "x"),
            history
        )
        assertEquals("4/8분기 확인", enriched.quarterlyCoverage)
        assertTrue(enriched.quarterlyTrend.contains("동일 회계범위(CFS)"))
    }

    @Test
    fun enrichmentKeepsFourDimensionsAndAddsQuarterlyFields() {
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
            points = (1..8).map { i ->
                QuarterlyPoint("202${4 + (i - 1) / 4}Q${(i - 1) % 4 + 1}", 2024 + (i - 1) / 4, (i - 1) % 4 + 1, 100.0 + i, 10.0, 10.0, 5.0, 2.0, "CFS", "DIRECT_3M")
            }
        )
        val enriched = QuarterlyTrendAnalyzer.enrich(base, history)
        assertEquals("8/8분기 확인", enriched.quarterlyCoverage)
        assertTrue(enriched.quarterlyTrend.isNotBlank())
        assertTrue(enriched.quarterlySignal.isNotBlank())
    }
}

package com.krstock.v3.data.repository

import com.krstock.v3.data.model.DataStatus
import org.junit.Assert.*
import org.junit.Test

class StockRepositoryExtremeTest {

    private val stocks = StockRepository.getAllStocks()

    @Test
    fun bundledDatasetIsExplicitlyDemoOnly() {
        assertTrue(stocks.isNotEmpty())
        assertTrue(stocks.all { it.status == DataStatus.DEMO })
        assertTrue(stocks.all { it.dataSource.contains("DEMO") })
    }

    @Test
    fun issuerIdsAreUniqueAndSixDigits() {
        assertEquals(stocks.size, stocks.map { it.issuerId }.toSet().size)
        assertTrue(stocks.all { it.issuerId.matches(Regex("\\d{6}")) })
    }

    @Test
    fun unknownIssuerNeverFallsBackToSamsung() {
        assertNull(StockRepository.getStockDetail("999999"))
        assertNull(StockRepository.getStockDetail(""))
        assertNull(StockRepository.getStockDetail("DROP TABLE"))
    }

    @Test
    fun knownIssuerReturnsExactCompany() {
        val detail = StockRepository.getStockDetail("000660")
        assertNotNull(detail)
        assertEquals("000660", detail!!.summary.issuerId)
        assertEquals("SK하이닉스", detail.summary.name)
    }

    @Test
    fun completeCompositeScoreEqualsFourMetricAverage() {
        stocks.filter { it.isCompositeComplete }.forEach { stock ->
            val expected = listOf(
                stock.m01RevGrowth.percentileScore!!,
                stock.m02OpMargin.percentileScore!!,
                stock.m03Per.percentileScore!!,
                stock.m04Price6m.percentileScore!!
            ).average()
            assertEquals(expected, stock.compositeScore!!, 0.0000001)
        }
    }

    @Test
    fun incompleteStocksCannotReceiveCompositeScoreOrRank() {
        stocks.filter { !it.isCompositeComplete }.forEach { stock ->
            assertNull(stock.compositeScore)
            assertNull(stock.rankOrder)
        }
    }

    @Test
    fun rankingOrderIsDeterministicAndDescendingByScore() {
        val ranked = stocks.filter { it.rankOrder != null }
        assertEquals((1..ranked.size).toList(), ranked.map { it.rankOrder })
        ranked.zipWithNext().forEach { (a, b) ->
            assertTrue(a.compositeScore!! >= b.compositeScore!!)
        }
    }

    @Test
    fun rankOneIsActuallyHighestScore() {
        val ranked = stocks.filter { it.rankOrder != null }
        val highest = ranked.maxByOrNull { it.compositeScore!! }
        assertEquals(1, highest?.rankOrder)
    }

    @Test
    fun metricIdsAndUnitsAreStable() {
        stocks.forEach { stock ->
            assertEquals("M01", stock.m01RevGrowth.id)
            assertEquals("M02", stock.m02OpMargin.id)
            assertEquals("M03", stock.m03Per.id)
            assertEquals("M04", stock.m04Price6m.id)
            assertEquals("%", stock.m01RevGrowth.unit)
            assertEquals("%", stock.m02OpMargin.unit)
            assertEquals("배", stock.m03Per.unit)
            assertEquals("%", stock.m04Price6m.unit)
        }
    }

    @Test
    fun percentileScoresStayInsideZeroToHundred() {
        stocks.flatMap {
            listOf(it.m01RevGrowth, it.m02OpMargin, it.m03Per, it.m04Price6m)
        }.mapNotNull { it.percentileScore }.forEach { score ->
            assertTrue("percentile out of range: $score", score in 0.0..100.0)
        }
    }

    @Test
    fun availableMetricAlwaysHasRawValueAndScore() {
        stocks.flatMap {
            listOf(it.m01RevGrowth, it.m02OpMargin, it.m03Per, it.m04Price6m)
        }.forEach { metric ->
            if (metric.isAvailable) {
                assertNotNull(metric.rawValue)
                assertNotNull(metric.percentileScore)
                assertNull(metric.reason)
            } else {
                assertTrue(!metric.reason.isNullOrBlank())
            }
        }
    }

    @Test
    fun metricEducationContentIsNeverBlank() {
        stocks.flatMap {
            listOf(it.m01RevGrowth, it.m02OpMargin, it.m03Per, it.m04Price6m)
        }.forEach { metric ->
            assertTrue(metric.description.isNotBlank())
            assertTrue(metric.interpretation.isNotBlank())
            assertTrue(metric.caution.isNotBlank())
        }
    }

    @Test
    fun financialCompanyDoesNotFakeIncompatibleMetrics() {
        val finance = stocks.first { it.issuerId == "055550" }
        assertTrue(finance.isFinancial)
        assertFalse(finance.m02OpMargin.isAvailable)
        assertFalse(finance.m03Per.isAvailable)
        assertFalse(finance.isCompositeComplete)
    }

    @Test
    fun lossMakingCompanyDoesNotFakePer() {
        val loss = stocks.first { it.issuerId == "247540" }
        assertTrue(loss.isLossMaking)
        assertFalse(loss.m03Per.isAvailable)
        assertNull(loss.m03Per.rawValue)
        assertFalse(loss.isCompositeComplete)
    }

    @Test
    fun searchByNameCodeSectorAndMarketWorks() {
        assertTrue(StockRepository.searchStocks("삼성전자").any { it.issuerId == "005930" })
        assertTrue(StockRepository.searchStocks("000660").any { it.name == "SK하이닉스" })
        assertTrue(StockRepository.searchStocks("바이오").isNotEmpty())
        assertTrue(StockRepository.searchStocks("KOSDAQ").all { it.market == "KOSDAQ" })
    }

    @Test
    fun blankSearchReturnsSameRankedDataset() {
        assertEquals(stocks, StockRepository.searchStocks(""))
        assertEquals(stocks, StockRepository.searchStocks("   "))
    }

    @Test
    fun everyDetailHasRichAnalysisSections() {
        stocks.forEach { stock ->
            val report = StockRepository.getStockDetail(stock.issuerId)!!.report
            assertTrue(report.oneLineView.isNotBlank())
            assertTrue(report.quantSummary.isNotBlank())
            assertTrue(report.businessQuality.isNotBlank())
            assertTrue(report.valuationView.isNotBlank())
            assertTrue(report.momentumView.isNotBlank())
            assertTrue(report.dataLimitations.contains("DEMO"))
            assertTrue(report.positiveFactors.isNotEmpty())
            assertTrue(report.riskFactors.isNotEmpty())
            assertTrue(report.nextVerificationConditions.size >= 5)
        }
    }

    @Test
    fun bundledDemoDoesNotFabricateNewsOrFilings() {
        stocks.forEach { stock ->
            val detail = StockRepository.getStockDetail(stock.issuerId)!!
            assertTrue(detail.news.isEmpty())
            assertTrue(detail.filings.isEmpty())
        }
    }

    @Test
    fun datasetContainsBothCompleteAndMissingnessCases() {
        assertTrue(stocks.any { it.isCompositeComplete })
        assertTrue(stocks.any { !it.isCompositeComplete })
        assertTrue(stocks.any { it.market == "KOSPI" })
        assertTrue(stocks.any { it.market == "KOSDAQ" })
    }

    @Test
    fun repeatedReadsAreStableUnderStress() {
        val baseline = stocks.joinToString("|") { "${it.issuerId}:${it.rankOrder}:${it.compositeScore}" }
        repeat(10_000) {
            val current = StockRepository.getAllStocks()
                .joinToString("|") { s -> "${s.issuerId}:${s.rankOrder}:${s.compositeScore}" }
            assertEquals(baseline, current)
        }
    }
}

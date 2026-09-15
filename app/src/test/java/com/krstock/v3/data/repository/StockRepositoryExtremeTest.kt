package com.krstock.v3.data.repository

import com.krstock.v3.data.generated.GeneratedKosdaqMaster
import com.krstock.v3.data.generated.GeneratedKospiMaster
import com.krstock.v3.data.generated.GeneratedRealQuantSnapshot
import com.krstock.v3.data.model.DataStatus
import org.junit.Assert.*
import org.junit.Test

class StockRepositoryExtremeTest {

    private val stocks = StockRepository.getAllStocks()
    private val kospi = stocks.filter { it.market == "KOSPI" }
    private val kosdaq = stocks.filter { it.market == "KOSDAQ" }

    @Test
    fun officialUniverseAndRealSnapshotCountsAreExact() {
        assertEquals(832, GeneratedKospiMaster.issuerCount)
        assertEquals(1817, GeneratedKosdaqMaster.issuerCount)
        assertEquals(2649, GeneratedRealQuantSnapshot.universeCount)
        assertEquals(2649, GeneratedRealQuantSnapshot.rows.size)
        assertEquals(2649, stocks.size)
        assertEquals(832, kospi.size)
        assertEquals(1817, kosdaq.size)
        assertEquals(1462, GeneratedRealQuantSnapshot.completeCount)
        assertEquals(1462, StockRepository.completeCount())
    }

    @Test
    fun committedRealCoverageMatchesAuditedSnapshot() {
        assertEquals(2469, GeneratedRealQuantSnapshot.m01Available)
        assertEquals(2380, GeneratedRealQuantSnapshot.m02Available)
        assertEquals(1677, GeneratedRealQuantSnapshot.m03Available)
        assertEquals(2611, GeneratedRealQuantSnapshot.m04Available)
        assertEquals("2026-09-15", GeneratedRealQuantSnapshot.snapshotDate)
        assertEquals("2026-09-14", GeneratedRealQuantSnapshot.priceCutoffDate)
        assertTrue(GeneratedRealQuantSnapshot.dartFileName.startsWith("2026_2Q_PL_"))
        assertTrue(GeneratedRealQuantSnapshot.dartFileName.endsWith(".zip"))
    }

    @Test
    fun marketIdentityAndIssueCodesNeverDrift() {
        assertEquals(setOf("KOSPI", "KOSDAQ"), stocks.map { it.market }.toSet())
        assertEquals(stocks.size, stocks.map { it.issuerId }.toSet().size)
        assertTrue(stocks.all { it.issuerId.matches(Regex("[0-9A-Z]{6}")) })
        assertTrue(kospi.any { !it.issuerId.all(Char::isDigit) })
        assertTrue(kosdaq.any { !it.issuerId.all(Char::isDigit) })
        assertEquals(stocks.map { it.issuerId }.toSet(), GeneratedRealQuantSnapshot.rows.map { it.code }.toSet())
    }

    @Test
    fun representativeCompaniesExistInCorrectMarkets() {
        fun stock(code: String) = stocks.single { it.issuerId == code }
        assertEquals("삼성전자", stock("005930").name)
        assertEquals("KOSPI", stock("005930").market)
        assertEquals("SK하이닉스", stock("000660").name)
        assertEquals("KOSPI", stock("000660").market)
        assertEquals("삼천당제약", stock("000250").name)
        assertEquals("KOSDAQ", stock("000250").market)
    }

    @Test
    fun unknownIssuerNeverFallsBackToAnotherCompany() {
        listOf("999999", "", "DROP TABLE", "??????", "<script>").forEach {
            assertNull(StockRepository.getStockDetail(it))
        }
    }

    @Test
    fun everyMetricObeysAvailabilityProvenanceContract() {
        stocks.forEach { stock ->
            val metrics = listOf(stock.m01RevGrowth, stock.m02OpMargin, stock.m03Per, stock.m04Price6m)
            assertEquals(listOf("M01", "M02", "M03", "M04"), metrics.map { it.id })
            assertEquals(listOf("%", "%", "배", "%"), metrics.map { it.unit })
            metrics.forEach { metric ->
                assertTrue(metric.description.isNotBlank())
                assertTrue(metric.interpretation.isNotBlank())
                assertTrue(metric.caution.isNotBlank())
                assertTrue(metric.source.isNotBlank())
                assertTrue(metric.asOfDate.isNotBlank())
                if (metric.isAvailable) {
                    assertNotNull(metric.rawValue)
                    assertNotNull(metric.percentileScore)
                    assertTrue(metric.percentileScore!! in 0.0..100.0)
                    assertNull(metric.reason)
                    assertTrue(metric.basis.isNotBlank())
                } else {
                    assertNull(metric.rawValue)
                    assertNull(metric.percentileScore)
                    assertTrue(!metric.reason.isNullOrBlank())
                }
            }
        }
    }

    @Test
    fun sourceAndBasisContractsMatchMetricDefinitions() {
        stocks.forEach { stock ->
            if (stock.m01RevGrowth.isAvailable) {
                assertTrue(stock.m01RevGrowth.source.contains("OpenDART"))
                assertTrue(stock.m01RevGrowth.basis.startsWith("2026Q2_3M_") || stock.m01RevGrowth.basis.startsWith("2026H1_YTD_"))
            }
            if (stock.m02OpMargin.isAvailable) {
                assertTrue(stock.m02OpMargin.source.contains("OpenDART"))
                assertTrue(stock.m02OpMargin.basis.startsWith("2026Q2_3M_") || stock.m02OpMargin.basis.startsWith("2026H1_YTD_"))
            }
            if (stock.m03Per.isAvailable) {
                assertTrue(stock.m03Per.source.contains("네이버"))
                assertTrue(stock.m03Per.rawValue!! > 0.0)
                assertTrue(stock.m03Per.basis.contains("_CLOSE/NAVER_EPS_"))
            }
            if (stock.m04Price6m.isAvailable) {
                assertTrue(stock.m04Price6m.source.contains("네이버"))
                assertTrue(stock.m04Price6m.basis.contains("->"))
            }
        }
    }

    @Test
    fun financialStocksNeverGetGeneralOperatingMargin() {
        stocks.filter { it.isFinancial }.forEach { stock ->
            assertFalse("financial stock had M02: ${stock.issuerId}", stock.m02OpMargin.isAvailable)
            assertNull(stock.m02OpMargin.rawValue)
        }
    }

    @Test
    fun lossMakingStocksNeverGetFakePer() {
        val lossMakers = stocks.filter { it.isLossMaking }
        assertTrue(lossMakers.size >= 900)
        lossMakers.forEach { stock ->
            assertFalse(stock.m03Per.isAvailable)
            assertNull(stock.m03Per.rawValue)
            assertNull(stock.m03Per.percentileScore)
        }
    }

    @Test
    fun compositeAndRankExistOnlyForFourMetricCompleteStocks() {
        val complete = stocks.filter { it.isCompositeComplete }
        val incomplete = stocks.filterNot { it.isCompositeComplete }
        assertEquals(1462, complete.size)
        complete.forEach { stock ->
            val scores = listOf(
                stock.m01RevGrowth.percentileScore,
                stock.m02OpMargin.percentileScore,
                stock.m03Per.percentileScore,
                stock.m04Price6m.percentileScore
            )
            assertTrue(scores.all { it != null })
            val expected = scores.filterNotNull().average()
            assertEquals(expected, stock.compositeScore!!, 0.00001)
            assertNotNull(stock.rankOrder)
        }
        incomplete.forEach { stock ->
            assertNull(stock.compositeScore)
            assertNull(stock.rankOrder)
        }
    }

    @Test
    fun ranksAreContiguousAndDescendingByScore() {
        val ranked = stocks.filter { it.rankOrder != null }.sortedBy { it.rankOrder }
        assertEquals((1..1462).toList(), ranked.map { it.rankOrder })
        ranked.zipWithNext().forEach { (a, b) ->
            assertTrue("rank inversion ${a.issuerId}/${b.issuerId}", a.compositeScore!! >= b.compositeScore!!)
        }
        assertEquals(1, ranked.maxByOrNull { it.compositeScore!! }?.rankOrder)
    }

    @Test
    fun identityFieldsAreNonBlankAndDatesAreSane() {
        stocks.forEach { stock ->
            assertTrue(stock.name.isNotBlank())
            assertTrue(stock.sector.isNotBlank())
            if (stock.listingDate.isNotBlank()) {
                assertTrue(stock.listingDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
                assertTrue(stock.listingDate <= StockRepository.quantSnapshotDate())
            }
        }
    }

    @Test
    fun searchByNameCodeSectorAndMarketWorksWithoutCrossMarketLeak() {
        assertTrue(StockRepository.searchStocks("삼성전자").any { it.issuerId == "005930" && it.market == "KOSPI" })
        assertTrue(StockRepository.searchStocks("000250").any { it.name == "삼천당제약" && it.market == "KOSDAQ" })
        assertEquals(832, StockRepository.searchStocks("KOSPI").size)
        assertEquals(1817, StockRepository.searchStocks("KOSDAQ").size)
        assertTrue(StockRepository.searchStocks("KOSPI").all { it.market == "KOSPI" })
        assertTrue(StockRepository.searchStocks("KOSDAQ").all { it.market == "KOSDAQ" })
    }

    @Test
    fun hostileSearchInputsCannotMutateDataset() {
        val first = StockRepository.getAllStocks()
        listOf("", "   ", "'", "\"", "%", "_", "DROP TABLE", "<script>", "가", "ZZZZZZ").forEach { q ->
            StockRepository.searchStocks(q)
            assertSame(first, StockRepository.getAllStocks())
            assertEquals(2649, first.size)
        }
    }

    @Test
    fun everyStockOpensExactDetailAndNoFakeNewsOrFilingsExist() {
        stocks.forEach { stock ->
            val detail = StockRepository.getStockDetail(stock.issuerId)
            assertNotNull(stock.issuerId, detail)
            assertEquals(stock.issuerId, detail!!.summary.issuerId)
            assertEquals(stock.market, detail.summary.market)
            assertTrue(detail.report.oneLineView.isNotBlank())
            assertTrue(detail.report.quantSummary.isNotBlank())
            assertTrue(detail.report.dataLimitations.contains("OpenDART"))
            assertTrue(detail.report.nextVerificationConditions.size >= 6)
            assertTrue(detail.news.isEmpty())
            assertTrue(detail.filings.isEmpty())
        }
    }

    @Test
    fun tenThousandRepeatedRepositoryReadsAreStable() {
        val first = StockRepository.getAllStocks()
        val fingerprint = first.joinToString("|") { "${it.market}:${it.issuerId}:${it.rankOrder}:${it.compositeScore}" }
        repeat(10_000) {
            val current = StockRepository.getAllStocks()
            assertSame(first, current)
            assertEquals(2649, current.size)
        }
        assertEquals(fingerprint, StockRepository.getAllStocks().joinToString("|") { "${it.market}:${it.issuerId}:${it.rankOrder}:${it.compositeScore}" })
    }
}

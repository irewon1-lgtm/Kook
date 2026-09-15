package com.krstock.v3.data.repository

import com.krstock.v3.data.generated.GeneratedKosdaqMaster
import com.krstock.v3.data.generated.GeneratedKospiMaster
import com.krstock.v3.data.model.DataStatus
import org.junit.Assert.*
import org.junit.Test

class StockRepositoryExtremeTest {

    private val stocks = StockRepository.getAllStocks()
    private val kospi = stocks.filter { it.market == "KOSPI" }
    private val kosdaq = stocks.filter { it.market == "KOSDAQ" }

    @Test
    fun officialMarketCountsMatchCommittedSnapshots() {
        assertEquals(832, GeneratedKospiMaster.issuerCount)
        assertEquals(1817, GeneratedKosdaqMaster.issuerCount)
        assertEquals(GeneratedKospiMaster.issuerCount, kospi.size)
        assertEquals(GeneratedKosdaqMaster.issuerCount, kosdaq.size)
        assertEquals(2649, stocks.size)
        assertEquals(2649, StockRepository.registeredCount())
        assertEquals(832, StockRepository.kospiCount())
        assertEquals(1817, StockRepository.kosdaqCount())
    }

    @Test
    fun onlyKospiAndKosdaqAreRegistered() {
        assertTrue(stocks.isNotEmpty())
        assertEquals(setOf("KOSPI", "KOSDAQ"), stocks.map { it.market }.toSet())
        assertTrue(stocks.all { it.status == DataStatus.REGISTERED })
        assertTrue(kospi.all { it.market == "KOSPI" })
        assertTrue(kosdaq.all { it.market == "KOSDAQ" })
    }

    @Test
    fun issuerIdsAreGloballyUniqueSixCharacterKrxCodes() {
        assertEquals(stocks.size, stocks.map { it.issuerId }.toSet().size)
        assertTrue(stocks.all { it.issuerId.matches(Regex("[0-9A-Z]{6}")) })
    }

    @Test
    fun modernAlphanumericKrxCodesArePreservedInBothMarkets() {
        val kospiModern = kospi.filter { !it.issuerId.all(Char::isDigit) }
        val kosdaqModern = kosdaq.filter { !it.issuerId.all(Char::isDigit) }
        assertTrue(kospiModern.isNotEmpty())
        assertTrue(kosdaqModern.isNotEmpty())
        assertTrue(kospiModern.all { it.issuerId.matches(Regex("[0-9A-Z]{6}")) })
        assertTrue(kosdaqModern.all { it.issuerId.matches(Regex("[0-9A-Z]{6}")) })
    }

    @Test
    fun officialMasterMetadataIsPresentForBothMarkets() {
        assertTrue(StockRepository.kospiSourceUrl().contains("kind.krx.co.kr"))
        assertTrue(StockRepository.kosdaqSourceUrl().contains("kind.krx.co.kr"))
        assertNotEquals("PENDING", StockRepository.kospiSnapshotDate())
        assertNotEquals("PENDING", StockRepository.kosdaqSnapshotDate())
        assertTrue(stocks.all { it.dataSource.contains("KRX KIND") })
    }

    @Test
    fun representativeCompaniesExistInCorrectMarkets() {
        val samsung = stocks.find { it.issuerId == "005930" }
        val hynix = stocks.find { it.issuerId == "000660" }
        val samchundang = stocks.find { it.issuerId == "000250" }
        assertNotNull(samsung)
        assertNotNull(hynix)
        assertNotNull(samchundang)
        assertEquals("삼성전자", samsung!!.name)
        assertEquals("KOSPI", samsung.market)
        assertEquals("SK하이닉스", hynix!!.name)
        assertEquals("KOSPI", hynix.market)
        assertEquals("삼천당제약", samchundang!!.name)
        assertEquals("KOSDAQ", samchundang.market)
    }

    @Test
    fun unknownIssuerNeverFallsBackToAnotherCompany() {
        assertNull(StockRepository.getStockDetail("999999"))
        assertNull(StockRepository.getStockDetail(""))
        assertNull(StockRepository.getStockDetail("DROP TABLE"))
        assertNull(StockRepository.getStockDetail("??????"))
    }

    @Test
    fun allFourMetricsStayUncollectedForEveryRegisteredStock() {
        stocks.forEach { stock ->
            val metrics = listOf(stock.m01RevGrowth, stock.m02OpMargin, stock.m03Per, stock.m04Price6m)
            assertTrue(metrics.all { !it.isAvailable })
            assertTrue(metrics.all { it.rawValue == null })
            assertTrue(metrics.all { it.percentileScore == null })
            assertTrue(metrics.all { !it.reason.isNullOrBlank() })
            assertNull(stock.compositeScore)
            assertNull(stock.rankOrder)
            assertFalse(stock.isCompositeComplete)
        }
    }

    @Test
    fun metricIdsUnitsAndEducationContentAreStableAcrossUniverse() {
        stocks.forEach { stock ->
            val metrics = listOf(stock.m01RevGrowth, stock.m02OpMargin, stock.m03Per, stock.m04Price6m)
            assertEquals(listOf("M01", "M02", "M03", "M04"), metrics.map { it.id })
            assertEquals(listOf("%", "%", "배", "%"), metrics.map { it.unit })
            assertTrue(metrics.all { it.description.isNotBlank() })
            assertTrue(metrics.all { it.interpretation.isNotBlank() })
            assertTrue(metrics.all { it.caution.isNotBlank() })
        }
    }

    @Test
    fun identityFieldsAreNonBlankAndListingDatesAreSane() {
        stocks.forEach { stock ->
            assertTrue(stock.name.isNotBlank())
            assertTrue(stock.sector.isNotBlank())
            if (stock.listingDate.isNotBlank()) {
                assertTrue("bad date ${stock.listingDate}", stock.listingDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
                assertTrue("future listing date ${stock.listingDate}", stock.listingDate <= stock.asOfDate)
            }
        }
    }

    @Test
    fun searchByNameCodeSectorAndMarketWorksWithoutCrossMarketLeak() {
        assertTrue(StockRepository.searchStocks("삼성전자").any { it.issuerId == "005930" && it.market == "KOSPI" })
        assertTrue(StockRepository.searchStocks("000250").any { it.name == "삼천당제약" && it.market == "KOSDAQ" })
        assertEquals(kospi.size, StockRepository.searchStocks("KOSPI").size)
        assertEquals(kosdaq.size, StockRepository.searchStocks("KOSDAQ").size)
        assertTrue(StockRepository.searchStocks("KOSPI").all { it.market == "KOSPI" })
        assertTrue(StockRepository.searchStocks("KOSDAQ").all { it.market == "KOSDAQ" })
    }

    @Test
    fun hostileAndOddSearchInputsDoNotCrashOrMutateDataset() {
        val baselineSize = stocks.size
        listOf("", "   ", "'", "\"", "%", "_", "DROP TABLE", "<script>", "가", "ZZZZZZ").forEach { q ->
            StockRepository.searchStocks(q)
            assertEquals(baselineSize, StockRepository.getAllStocks().size)
        }
    }

    @Test
    fun blankSearchReturnsSameDataset() {
        assertEquals(stocks, StockRepository.searchStocks(""))
        assertEquals(stocks, StockRepository.searchStocks("   "))
    }

    @Test
    fun everyRegisteredStockOpensExactDetailWithoutFabricatedAnalysis() {
        stocks.forEach { stock ->
            val detail = StockRepository.getStockDetail(stock.issuerId)
            assertNotNull(stock.issuerId, detail)
            assertEquals(stock.issuerId, detail!!.summary.issuerId)
            assertEquals(stock.market, detail.summary.market)
            val report = detail.report
            assertTrue(report.oneLineView.contains("실종목 등록 완료"))
            assertTrue(report.quantSummary.contains("미수집"))
            assertTrue(report.valuationView.contains("미수집"))
            assertTrue(report.momentumView.contains("미수집"))
            assertTrue(report.dataLimitations.contains("KRX KIND"))
            assertTrue(report.nextVerificationConditions.size >= 6)
            assertTrue(detail.news.isEmpty())
            assertTrue(detail.filings.isEmpty())
        }
    }

    @Test
    fun tenThousandRepeatedRepositoryReadsAreStableAndSameSnapshot() {
        val first = StockRepository.getAllStocks()
        val firstHead = first.take(10).map { "${it.market}:${it.issuerId}:${it.name}" }
        val firstTail = first.takeLast(10).map { "${it.market}:${it.issuerId}:${it.name}" }
        repeat(10_000) {
            val current = StockRepository.getAllStocks()
            assertSame(first, current)
            assertEquals(2649, current.size)
        }
        assertEquals(firstHead, StockRepository.getAllStocks().take(10).map { "${it.market}:${it.issuerId}:${it.name}" })
        assertEquals(firstTail, StockRepository.getAllStocks().takeLast(10).map { "${it.market}:${it.issuerId}:${it.name}" })
    }
}

package com.krstock.v3.data.repository

import com.krstock.v3.data.generated.GeneratedKospiMaster
import com.krstock.v3.data.model.DataStatus
import org.junit.Assert.*
import org.junit.Test

class StockRepositoryExtremeTest {

    private val stocks = StockRepository.getAllStocks()

    @Test
    fun kospiMasterHasExpectedRealScale() {
        assertTrue("too few KOSPI issuers: ${stocks.size}", stocks.size >= 700)
        assertTrue("too many KOSPI issuers: ${stocks.size}", stocks.size <= 1200)
        assertEquals(GeneratedKospiMaster.issuerCount, stocks.size)
    }

    @Test
    fun allRegisteredStocksAreKospiOnly() {
        assertTrue(stocks.isNotEmpty())
        assertTrue(stocks.all { it.market == "KOSPI" })
        assertTrue(stocks.none { it.market == "KOSDAQ" })
        assertTrue(stocks.all { it.status == DataStatus.REGISTERED })
    }

    @Test
    fun issuerIdsAreUniqueAndSixDigits() {
        assertEquals(stocks.size, stocks.map { it.issuerId }.toSet().size)
        assertTrue(stocks.all { it.issuerId.matches(Regex("\\d{6}")) })
    }

    @Test
    fun officialMasterMetadataIsPresent() {
        assertTrue(StockRepository.masterSourceUrl().contains("kind.krx.co.kr"))
        assertNotEquals("PENDING", StockRepository.masterSnapshotDate())
        assertTrue(stocks.all { it.dataSource.contains("KRX KIND") })
    }

    @Test
    fun samsungAndSkHynixExistWithExactCodes() {
        val samsung = stocks.find { it.issuerId == "005930" }
        val hynix = stocks.find { it.issuerId == "000660" }
        assertNotNull(samsung)
        assertNotNull(hynix)
        assertEquals("삼성전자", samsung!!.name)
        assertEquals("SK하이닉스", hynix!!.name)
    }

    @Test
    fun unknownIssuerNeverFallsBackToAnotherCompany() {
        assertNull(StockRepository.getStockDetail("999999"))
        assertNull(StockRepository.getStockDetail(""))
        assertNull(StockRepository.getStockDetail("DROP TABLE"))
    }

    @Test
    fun allFourMetricsStayUncollectedAtRegistrationStage() {
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
    fun metricIdsUnitsAndEducationContentAreStable() {
        stocks.take(50).forEach { stock ->
            val metrics = listOf(stock.m01RevGrowth, stock.m02OpMargin, stock.m03Per, stock.m04Price6m)
            assertEquals(listOf("M01", "M02", "M03", "M04"), metrics.map { it.id })
            assertEquals(listOf("%", "%", "배", "%"), metrics.map { it.unit })
            assertTrue(metrics.all { it.description.isNotBlank() })
            assertTrue(metrics.all { it.interpretation.isNotBlank() })
            assertTrue(metrics.all { it.caution.isNotBlank() })
        }
    }

    @Test
    fun searchByNameCodeSectorAndMarketWorks() {
        assertTrue(StockRepository.searchStocks("삼성전자").any { it.issuerId == "005930" })
        assertTrue(StockRepository.searchStocks("000660").any { it.name == "SK하이닉스" })
        assertEquals(stocks.size, StockRepository.searchStocks("KOSPI").size)
        val firstWithSector = stocks.first { it.sector.isNotBlank() && it.sector != "기타" }
        val token = firstWithSector.sector.take(2)
        assertTrue(StockRepository.searchStocks(token).isNotEmpty())
    }

    @Test
    fun blankSearchReturnsSameDataset() {
        assertEquals(stocks, StockRepository.searchStocks(""))
        assertEquals(stocks, StockRepository.searchStocks("   "))
    }

    @Test
    fun registrationDetailExplicitlyRefusesFakeInvestmentAnalysis() {
        stocks.take(30).forEach { stock ->
            val detail = StockRepository.getStockDetail(stock.issuerId)!!
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
    fun repeatedReadsAreStableUnderStress() {
        val baseline = stocks.joinToString("|") { "${it.issuerId}:${it.name}" }
        repeat(2_000) {
            val current = StockRepository.getAllStocks().joinToString("|") { s -> "${s.issuerId}:${s.name}" }
            assertEquals(baseline, current)
        }
    }
}

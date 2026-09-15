package com.krstock.v3.data.candidate

import com.krstock.v3.data.model.DataStatus
import com.krstock.v3.data.model.MetricValue
import com.krstock.v3.data.model.StockSummary
import org.junit.Assert.*
import org.junit.Test

class FinalCandidateEngineTest {

    @Test
    fun selectsOnlyCompletePositivePerStocksAndKeepsExistingRankOrder() {
        val stocks = listOf(
            stock("000003", rank = 3, per = 9.0, complete = true),
            stock("000001", rank = 1, per = 11.0, complete = true),
            stock("000002", rank = 2, per = -4.0, complete = true),
            stock("000004", rank = 4, per = 8.0, complete = false),
            stock("000005", rank = 5, per = 7.0, complete = true)
        )

        val result = FinalCandidateEngine.select(stocks, limit = 3)

        assertEquals(listOf("000001", "000003", "000005"), result.map { it.issuerId })
        assertEquals(listOf(1, 2, 3), result.map { it.candidateRank })
        assertEquals(listOf(1, 3, 5), result.map { it.sourceRank })
        assertTrue(result.all { it.m03.rawValue > 0.0 })
    }

    @Test
    fun standardRecordCarriesAuditFieldsWithoutInventingNumbers() {
        val source = stock("123456", rank = 7, per = 12.5, complete = true)
        val row = FinalCandidateEngine.select(listOf(source), limit = 1).single()

        assertEquals(FinalCandidateEngine.SCHEMA_VERSION, row.schemaVersion)
        assertEquals(FinalCandidateEngine.POLICY_VERSION, row.policyVersion)
        assertEquals("123456", row.issuerId)
        assertEquals("회사123456", row.name)
        assertEquals("KOSPI", row.market)
        assertEquals("테스트업종", row.sector)
        assertEquals(77.0, row.compositeScore, 0.0)
        assertEquals("2026-09-15", row.financialSnapshotDate)
        assertEquals("2026-09-15", row.priceCutoffDate)
        assertEquals(10.0, row.m01.rawValue, 0.0)
        assertEquals(12.5, row.m03.rawValue, 0.0)
        assertEquals("M03_BASIS", row.m03.basis)
        assertEquals("FINAL_RESEARCH_CANDIDATE", row.researchStatus)
        assertTrue(row.selectionFlags.contains("FOUR_METRICS_COMPLETE"))
        assertTrue(row.selectionFlags.contains("POSITIVE_TRAILING_PER"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedCandidateLimit() {
        FinalCandidateEngine.select(emptyList(), limit = 101)
    }

    private fun stock(
        code: String,
        rank: Int,
        per: Double,
        complete: Boolean
    ): StockSummary {
        val m01 = metric("M01", 10.0, complete)
        val m02 = metric("M02", 15.0, complete)
        val m03 = metric("M03", per, complete)
        val m04 = metric("M04", 20.0, complete)
        return StockSummary(
            issuerId = code,
            name = "회사$code",
            market = "KOSPI",
            sector = "테스트업종",
            listingDate = "2020-01-01",
            isFinancial = false,
            isLossMaking = per < 0.0,
            m01RevGrowth = m01,
            m02OpMargin = m02,
            m03Per = m03,
            m04Price6m = m04,
            compositeScore = if (complete) 77.0 else null,
            rankOrder = if (complete) rank else null,
            isCompositeComplete = complete,
            asOfDate = "2026-09-15",
            dataSource = "TEST",
            status = DataStatus.REAL
        )
    }

    private fun metric(id: String, raw: Double, available: Boolean): MetricValue = MetricValue(
        id = id,
        nameKo = id,
        rawValue = if (available) raw else null,
        percentileScore = if (available) 70.0 else null,
        unit = if (id == "M03") "배" else "%",
        isAvailable = available,
        reason = if (available) null else "TEST_MISSING",
        source = "TEST_SOURCE",
        basis = "${id}_BASIS",
        asOfDate = "2026-09-15"
    )
}

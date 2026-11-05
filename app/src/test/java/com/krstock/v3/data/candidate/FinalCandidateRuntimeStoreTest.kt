package com.krstock.v3.data.candidate

import com.krstock.v3.data.model.CandidateMetricSnapshot
import com.krstock.v3.data.model.FinalCandidateRecord
import com.krstock.v3.data.model.FinancialSafetyRecord
import com.krstock.v3.data.model.FinancialSafetySnapshot
import com.krstock.v3.data.model.FinancialSafetyStatus
import com.krstock.v3.data.stage.FinancialSafetyRuntimeStore
import com.krstock.v3.data.stage.FinancialSafetySnapshotParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalCandidateRuntimeStoreTest {
    @After
    fun tearDown() {
        FinalCandidateRuntimeStore.clear()
        FinancialSafetyRuntimeStore.clear()
    }

    @Test
    fun exactSnapshotDateIsRequired() {
        installSafety("2026-09-16")
        FinalCandidateRuntimeStore.install("2026-09-16", listOf(row(1)))
        assertEquals(1, FinalCandidateRuntimeStore.current("2026-09-16").size)
        assertTrue(FinalCandidateRuntimeStore.current("2026-09-15").isEmpty())
    }

    @Test
    fun sameDateCandidatesAreHiddenWithoutCurrentSafety() {
        FinalCandidateRuntimeStore.install("2026-09-16", listOf(row(1)))
        assertTrue(FinalCandidateRuntimeStore.current("2026-09-16").isEmpty())
    }

    @Test
    fun candidatesAreHiddenWhenSafetyBelongsToAnotherSnapshot() {
        installSafety("2026-09-15")
        FinalCandidateRuntimeStore.install("2026-09-16", listOf(row(1)))
        assertTrue(FinalCandidateRuntimeStore.current("2026-09-16").isEmpty())
    }

    @Test
    fun clearingSameDateSafetyImmediatelyHidesPreviouslyValidCandidates() {
        installSafety("2026-09-16")
        FinalCandidateRuntimeStore.install("2026-09-16", listOf(row(1)))
        assertEquals(1, FinalCandidateRuntimeStore.current("2026-09-16").size)
        FinancialSafetyRuntimeStore.clear()
        assertTrue(FinalCandidateRuntimeStore.current("2026-09-16").isEmpty())
    }

    @Test
    fun candidatesRequireSafetyPassForEveryIssuer() {
        installSafety("2026-09-16", FinancialSafetyStatus.HOLD)
        FinalCandidateRuntimeStore.install("2026-09-16", listOf(row(1)))
        assertTrue(FinalCandidateRuntimeStore.current("2026-09-16").isEmpty())
    }

    @Test
    fun candidateWithoutIssuerSafetyIsHidden() {
        installSafety("2026-09-16")
        FinalCandidateRuntimeStore.install("2026-09-16", listOf(row(1).copy(issuerId = "000002")))
        assertTrue(FinalCandidateRuntimeStore.current("2026-09-16").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonSafetyPassRow() {
        FinalCandidateRuntimeStore.install("2026-09-16", listOf(row(1, safety = "FAIL")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRankGap() {
        FinalCandidateRuntimeStore.install("2026-09-16", listOf(row(2)))
    }

    private fun metric(id: String) = CandidateMetricSnapshot(
        id = id,
        rawValue = 10.0,
        percentileScore = 70.0,
        basis = "TEST",
        asOfDate = "2026-09-16",
    )

    private fun installSafety(date: String, status: FinancialSafetyStatus = FinancialSafetyStatus.PASS) {
        val record = FinancialSafetyRecord(
            issuerId = "000001",
            status = status,
            reasonCode = if (status == FinancialSafetyStatus.PASS) "PASS" else "MISSING_CURRENT_ASSETS",
            scope = "CFS",
            debtToEquityPct = if (status == FinancialSafetyStatus.PASS) 100.0 else null,
            currentRatioPct = if (status == FinancialSafetyStatus.PASS) 150.0 else null,
            accountingIdentityGapPct = if (status == FinancialSafetyStatus.PASS) 0.1 else null,
            basis = "CFS TEST",
        )
        FinancialSafetyRuntimeStore.install(
            FinancialSafetySnapshot(
                policyVersion = FinancialSafetySnapshotParser.POLICY_VERSION,
                snapshotDate = date,
                source = "OpenDART",
                sourceFile = "fixture.zip",
                passCount = if (status == FinancialSafetyStatus.PASS) 1 else 0,
                failCount = 0,
                holdCount = if (status == FinancialSafetyStatus.HOLD) 1 else 0,
                notApplicableCount = 0,
                records = mapOf(record.issuerId to record),
            )
        )
    }

    private fun row(rank: Int, safety: String = "PASS") = FinalCandidateRecord(
        schemaVersion = "KR4_FINAL_CANDIDATE_V2",
        policyVersion = "STAGE4567_SAFETY_AND_RELATIVE_PER_BAND_V1",
        candidateRank = rank,
        sourceRank = rank,
        issuerId = "%06d".format(rank),
        name = "회사$rank",
        market = "KOSPI",
        sector = "제조",
        compositeScore = 80.0,
        financialSnapshotDate = "2026-09-16",
        priceCutoffDate = "2026-09-15",
        m01 = metric("M01"),
        m02 = metric("M02"),
        m03 = metric("M03"),
        m04 = metric("M04"),
        selectionFlags = listOf("FINANCIAL_SAFETY_PASS", "VALUATION_BAND_CLASSIFIED"),
        researchStatus = "FINAL_RESEARCH_CANDIDATE",
        selectionReasonKo = "TEST",
        financialSafetyStatus = safety,
        valuationBand = "MID_PER",
        valuationPercentile = 70.0,
    )
}

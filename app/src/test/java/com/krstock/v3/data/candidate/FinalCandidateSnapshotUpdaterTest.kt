package com.krstock.v3.data.candidate

import com.krstock.v3.data.model.DataStatus
import com.krstock.v3.data.model.MetricValue
import com.krstock.v3.data.model.StockSummary
import com.krstock.v3.data.model.FinancialSafetyRecord
import com.krstock.v3.data.model.FinancialSafetySnapshot
import com.krstock.v3.data.model.FinancialSafetyStatus
import com.krstock.v3.data.stage.FinancialSafetyRuntimeStore
import com.krstock.v3.data.stage.FinancialSafetySnapshotParser
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class FinalCandidateSnapshotUpdaterTest {
    @After
    fun tearDown() {
        FinancialSafetyRuntimeStore.clear()
        FinalCandidateRuntimeStore.clear()
    }

    @Test(expected = IllegalArgumentException::class)
    fun sameDateCandidatePayloadRequiresCurrentStage4() {
        val root = document()
        parse(root)
    }

    @Test
    fun currentSafetyPassPreservesThePublishedCandidateOrder() {
        val root = document()
        installSafety(root)
        val parsed = parse(root)
        assertEquals(root.getInt("candidate_count"), parsed.size)
        assertEquals(root.getJSONArray("candidates").getJSONObject(0).getString("code"), parsed.first().issuerId)
        assertEquals((1..parsed.size).toList(), parsed.map { it.candidateRank })
    }

    @Test(expected = IllegalArgumentException::class)
    fun candidateRowsMustMatchTheActiveSnapshotDate() {
        val root = document()
        installSafety(root)
        root.getJSONArray("candidates").getJSONObject(0).put("financial_snapshot_date", "2000-01-01")
        parse(root)
    }

    @Test(expected = IllegalArgumentException::class)
    fun candidateCannotClaimPassWhenCurrentStage4IsHold() {
        val root = document()
        installSafety(root, firstStatus = FinancialSafetyStatus.HOLD)
        parse(root)
    }

    @Test
    fun sameDateCorrectionsRejectObsoleteCandidateRankingAndMetrics() {
        val root = document()
        installSafety(root)
        val active = activeStocks(root)
        val first = active.values.first()
        val corrections = listOf(
            first.copy(rankOrder = requireNotNull(first.rankOrder) + 1),
            first.copy(compositeScore = requireNotNull(first.compositeScore) + 1.0),
            first.copy(m01RevGrowth = first.m01RevGrowth.copy(rawValue = requireNotNull(first.m01RevGrowth.rawValue) + 1.0)),
            first.copy(m02OpMargin = first.m02OpMargin.copy(percentileScore = requireNotNull(first.m02OpMargin.percentileScore) - 1.0)),
            first.copy(m03Per = first.m03Per.copy(rawValue = requireNotNull(first.m03Per.rawValue) + 1.0)),
            first.copy(m04Price6m = first.m04Price6m.copy(percentileScore = requireNotNull(first.m04Price6m.percentileScore) - 1.0)),
        )
        corrections.forEach { corrected ->
            assertThrows(IllegalArgumentException::class.java) { parse(root, active + (first.issuerId to corrected)) }
        }
    }

    @Test
    fun candidatePriceCutoffMustMatchCurrentQuant() {
        val root = document()
        installSafety(root)
        assertThrows(IllegalArgumentException::class.java) {
            FinalCandidateSnapshotUpdater.parseAndValidate(
                root.toString(), root.getString("snapshot_date_kst"), activeStocks(root), "2000-01-01",
            )
        }
    }

    private fun parse(root: JSONObject, active: Map<String, StockSummary> = activeStocks(root)) =
        FinalCandidateSnapshotUpdater.parseAndValidate(
            root.toString(), root.getString("snapshot_date_kst"), active, root.getString("price_cutoff_date_kst"),
        )

    private fun activeStocks(root: JSONObject): Map<String, StockSummary> {
        val candidates = root.getJSONArray("candidates")
        return (0 until candidates.length()).associate { index ->
            val row = candidates.getJSONObject(index)
            val metrics = row.getJSONObject("metrics")
            fun metric(id: String): MetricValue {
                val value = metrics.getJSONObject(id)
                return MetricValue(id, id, value.getDouble("raw"), value.getDouble("percentile"), "", true,
                    basis = value.getString("basis"), asOfDate = value.getString("as_of_date"))
            }
            val code = row.getString("code")
            code to StockSummary(
                issuerId = code, name = row.getString("name"), market = row.getString("market"),
                m01RevGrowth = metric("M01"), m02OpMargin = metric("M02"),
                m03Per = metric("M03"), m04Price6m = metric("M04"),
                compositeScore = row.getDouble("composite_score"), rankOrder = row.getInt("source_rank"),
                isCompositeComplete = true, asOfDate = root.getString("snapshot_date_kst"),
                dataSource = "published snapshot fixture", status = DataStatus.REAL,
            )
        }
    }

    private fun document(): JSONObject = JSONObject(File("../evidence/final_candidates.json").readText())

    private fun installSafety(root: JSONObject, firstStatus: FinancialSafetyStatus = FinancialSafetyStatus.PASS) {
        val candidates = root.getJSONArray("candidates")
        val records = (0 until candidates.length()).associate { index ->
            val row = candidates.getJSONObject(index)
            val safety = row.getJSONObject("financial_safety")
            val code = row.getString("code")
            code to FinancialSafetyRecord(
                issuerId = code,
                status = if (index == 0) firstStatus else FinancialSafetyStatus.PASS,
                reasonCode = "PASS",
                scope = "CFS",
                debtToEquityPct = safety.getDouble("debt_to_equity_pct"),
                currentRatioPct = safety.getDouble("current_ratio_pct"),
                accountingIdentityGapPct = safety.getDouble("identity_gap_pct"),
                basis = safety.getString("basis"),
            )
        }
        FinancialSafetyRuntimeStore.install(FinancialSafetySnapshot(
            policyVersion = FinancialSafetySnapshotParser.POLICY_VERSION,
            snapshotDate = root.getString("snapshot_date_kst"),
            source = "OpenDART",
            sourceFile = "published candidate fixture",
            passCount = records.size - if (firstStatus == FinancialSafetyStatus.HOLD) 1 else 0,
            failCount = 0,
            holdCount = if (firstStatus == FinancialSafetyStatus.HOLD) 1 else 0,
            notApplicableCount = 0,
            records = records,
        ))
    }
}

package com.krstock.v3.data.candidate

import com.krstock.v3.data.model.CandidateMetricSnapshot
import com.krstock.v3.data.model.FinalCandidateRecord
import com.krstock.v3.data.model.MetricValue
import com.krstock.v3.data.model.StockSummary

/**
 * Stage 6 shortlist engine.
 *
 * Policy is deliberately fail-closed:
 * - all four validated metrics must be present;
 * - the existing composite/rank must be present;
 * - trailing PER must be positive (loss-making/zero-EPS states are not final candidates);
 * - no new valuation or fundamental number is guessed here.
 *
 * Ordering reuses the existing KR4 four-axis rank, so this layer cannot silently
 * invent a second scoring formula. It only narrows the already-ranked universe.
 */
object FinalCandidateEngine {
    const val SCHEMA_VERSION = "KR4_FINAL_CANDIDATE_V1"
    const val POLICY_VERSION = "STAGE6_POSITIVE_PER_COMPLETE_TOP_RANK_V1"
    const val DEFAULT_LIMIT = 10

    fun select(
        stocks: List<StockSummary>,
        limit: Int = DEFAULT_LIMIT
    ): List<FinalCandidateRecord> {
        require(limit in 1..100) { "Final-candidate limit must be between 1 and 100" }

        val selected = stocks.asSequence()
            .filter(::isEligible)
            .sortedWith(
                compareBy<StockSummary> { it.rankOrder ?: Int.MAX_VALUE }
                    .thenBy { it.issuerId }
            )
            .distinctBy { it.issuerId }
            .take(limit)
            .toList()

        return selected.mapIndexed { index, stock -> toRecord(stock, index + 1) }
    }

    fun isEligible(stock: StockSummary): Boolean {
        if (!stock.isCompositeComplete) return false
        if (stock.rankOrder == null || stock.compositeScore == null) return false
        if (stock.m03Per.rawValue?.let { it > 0.0 } != true) return false

        return listOf(
            stock.m01RevGrowth,
            stock.m02OpMargin,
            stock.m03Per,
            stock.m04Price6m
        ).all { metric ->
            metric.isAvailable && metric.rawValue != null && metric.percentileScore != null
        }
    }

    private fun toRecord(stock: StockSummary, candidateRank: Int): FinalCandidateRecord {
        val sourceRank = requireNotNull(stock.rankOrder)
        val composite = requireNotNull(stock.compositeScore)
        val priceDate = stock.m03Per.asOfDate.ifBlank { stock.m04Price6m.asOfDate }

        val flags = buildList {
            add("FOUR_METRICS_COMPLETE")
            add("POSITIVE_TRAILING_PER")
            add("EXISTING_COMPOSITE_RANK_REUSED")
            if ((stock.m01RevGrowth.rawValue ?: 0.0) > 0.0) add("POSITIVE_REVENUE_GROWTH")
            if ((stock.m02OpMargin.rawValue ?: 0.0) > 0.0) add("POSITIVE_OPERATING_MARGIN")
            if ((stock.m04Price6m.rawValue ?: 0.0) > 0.0) add("POSITIVE_6M_MOMENTUM")
        }

        return FinalCandidateRecord(
            schemaVersion = SCHEMA_VERSION,
            policyVersion = POLICY_VERSION,
            candidateRank = candidateRank,
            sourceRank = sourceRank,
            issuerId = stock.issuerId,
            name = stock.name,
            market = stock.market,
            sector = stock.sector,
            compositeScore = composite,
            financialSnapshotDate = stock.asOfDate,
            priceCutoffDate = priceDate,
            m01 = metricSnapshot(stock.m01RevGrowth),
            m02 = metricSnapshot(stock.m02OpMargin),
            m03 = metricSnapshot(stock.m03Per),
            m04 = metricSnapshot(stock.m04Price6m),
            selectionFlags = flags,
            researchStatus = "FINAL_RESEARCH_CANDIDATE",
            selectionReasonKo = "4지표 검증 완료 · 실적 PER 양수 · 기존 종합순위 ${sourceRank}위 기준 최종 조사 후보"
        )
    }

    private fun metricSnapshot(metric: MetricValue): CandidateMetricSnapshot = CandidateMetricSnapshot(
        id = metric.id,
        rawValue = requireNotNull(metric.rawValue),
        percentileScore = requireNotNull(metric.percentileScore),
        basis = metric.basis,
        asOfDate = metric.asOfDate,
        source = metric.source
    )
}

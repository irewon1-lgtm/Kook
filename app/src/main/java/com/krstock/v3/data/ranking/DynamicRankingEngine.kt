package com.krstock.v3.data.ranking

import com.krstock.v3.data.model.MetricValue
import com.krstock.v3.data.model.StockSummary

enum class RankMetric(val id: String, val label: String) {
    M01("M01", "매출 증가율"),
    M02("M02", "영업이익률"),
    M03("M03", "실적 PER"),
    M04("M04", "6개월 상승률");

    companion object {
        val allIds: Set<String> = entries.map { it.id }.toSet()
    }
}

data class DynamicRankResult(
    val issuerId: String,
    val score: Double?,
    val rank: Int?,
    val selectedMetricCount: Int,
    val isEligible: Boolean
)

object DynamicRankingEngine {

    fun rank(
        stocks: List<StockSummary>,
        selectedMetricIds: Set<String>
    ): Map<String, DynamicRankResult> {
        require(selectedMetricIds.isNotEmpty()) { "At least one ranking metric must be selected" }
        require(selectedMetricIds.all { it in RankMetric.allIds }) {
            "Unknown ranking metric: ${selectedMetricIds - RankMetric.allIds}"
        }

        val rawScores = stocks.associate { stock ->
            val selected = selectedMetricIds.map { metricId -> metric(stock, metricId) }
            val eligible = selected.all { it.isAvailable && it.percentileScore != null }
            val score = if (eligible) selected.mapNotNull { it.percentileScore }.average() else null
            stock.issuerId to score
        }

        val ordered = stocks
            .mapNotNull { stock -> rawScores[stock.issuerId]?.let { score -> stock.issuerId to score } }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })

        val rankByCode = ordered.mapIndexed { index, pair -> pair.first to index + 1 }.toMap()

        return stocks.associate { stock ->
            val score = rawScores[stock.issuerId]
            stock.issuerId to DynamicRankResult(
                issuerId = stock.issuerId,
                score = score,
                rank = if (score != null) rankByCode[stock.issuerId] else null,
                selectedMetricCount = selectedMetricIds.size,
                isEligible = score != null
            )
        }
    }

    fun metric(stock: StockSummary, metricId: String): MetricValue = when (metricId) {
        "M01" -> stock.m01RevGrowth
        "M02" -> stock.m02OpMargin
        "M03" -> stock.m03Per
        "M04" -> stock.m04Price6m
        else -> error("Unknown metric id $metricId")
    }
}

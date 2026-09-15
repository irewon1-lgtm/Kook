package com.krstock.v3.data.model

data class QuarterlyPoint(
    val period: String,
    val fiscalYear: Int,
    val quarter: Int,
    val revenue: Double?,
    val operatingIncome: Double?,
    val operatingMargin: Double?,
    val revenueYoY: Double?,
    val revenueQoQ: Double?,
    val scope: String,
    val basis: String,
    val sourceFile: String = ""
)

data class QuarterlyHistory(
    val issuerId: String,
    val points: List<QuarterlyPoint> = emptyList(),
    val loaded: Boolean = false,
    val source: String = "",
    val generatedAt: String = "",
    val error: String? = null
) {
    val availableQuarterCount: Int
        get() = points.count { it.revenue != null }

    val hasFourQuarters: Boolean
        get() = availableQuarterCount >= 4

    val hasEightQuarters: Boolean
        get() = availableQuarterCount >= 8

    companion object {
        fun empty(issuerId: String) = QuarterlyHistory(issuerId = issuerId)
    }
}

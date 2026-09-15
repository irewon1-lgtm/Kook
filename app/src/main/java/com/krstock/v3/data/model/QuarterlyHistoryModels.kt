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
    val sourceFile: String = "",
    val reason: String? = null
) {
    val ordinal: Int get() = fiscalYear * 4 + quarter
    val isDirectQuarter: Boolean get() = basis.equals("DIRECT_3M", ignoreCase = true)
    val isReconstructedQuarter: Boolean
        get() = revenue != null && !isDirectQuarter && basis.isNotBlank()
}

data class QuarterlyHistory(
    val issuerId: String,
    val points: List<QuarterlyPoint> = emptyList(),
    val loaded: Boolean = false,
    val source: String = "",
    val generatedAt: String = "",
    val error: String? = null
) {
    private val orderedPoints: List<QuarterlyPoint>
        get() = points
            .filter { it.quarter in 1..4 && it.fiscalYear > 0 }
            .sortedWith(compareBy<QuarterlyPoint> { it.fiscalYear }.thenBy { it.quarter })
            .distinctBy { it.ordinal }

    val latestTargetPoint: QuarterlyPoint?
        get() = orderedPoints.lastOrNull()

    /**
     * The accounting scope is defined only by the latest target quarter.
     * If the latest quarter is missing, trend analysis fails closed instead of silently
     * shifting the "latest" observation backwards.
     */
    val comparisonScope: String
        get() = latestTargetPoint
            ?.takeIf { it.revenue != null }
            ?.scope
            .orEmpty()

    /**
     * Consecutive, same-scope suffix ending at the latest target quarter.
     * A missing quarter or CFS/OFS scope change stops the chain immediately.
     */
    val comparablePoints: List<QuarterlyPoint>
        get() {
            val ordered = orderedPoints
            val latest = ordered.lastOrNull() ?: return emptyList()
            if (latest.revenue == null) return emptyList()
            val scope = latest.scope
            val reversed = mutableListOf<QuarterlyPoint>()
            var expectedOrdinal = latest.ordinal
            for (index in ordered.lastIndex downTo 0) {
                val point = ordered[index]
                if (point.ordinal != expectedOrdinal) break
                if (point.revenue == null || point.scope != scope) break
                reversed += point
                expectedOrdinal -= 1
                if (reversed.size == 8) break
            }
            return reversed.asReversed()
        }

    val availableQuarterCount: Int
        get() = comparablePoints.size

    val directQuarterCount: Int
        get() = comparablePoints.count { it.isDirectQuarter }

    val reconstructedQuarterCount: Int
        get() = comparablePoints.count { it.isReconstructedQuarter }

    val hasFourQuarters: Boolean
        get() = availableQuarterCount >= 4

    val hasEightQuarters: Boolean
        get() = availableQuarterCount >= 8

    val hasTrailingGap: Boolean
        get() = latestTargetPoint?.revenue == null

    companion object {
        fun empty(issuerId: String) = QuarterlyHistory(issuerId = issuerId)
    }
}

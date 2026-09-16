package com.krstock.v3.data.model

/**
 * Stage 4 financial-safety state parsed from the exact active KR4 quant snapshot.
 * Values are source-backed OpenDART balance-sheet facts. Null remains null; the app
 * must never infer or backfill a missing ratio.
 */
enum class FinancialSafetyStatus {
    PASS,
    FAIL,
    HOLD,
    NOT_APPLICABLE,
}

data class FinancialSafetyRecord(
    val issuerId: String,
    val status: FinancialSafetyStatus,
    val reasonCode: String,
    val scope: String,
    val debtToEquityPct: Double?,
    val currentRatioPct: Double?,
    val accountingIdentityGapPct: Double?,
    val basis: String,
)

data class FinancialSafetySnapshot(
    val policyVersion: String,
    val snapshotDate: String,
    val source: String,
    val sourceFile: String,
    val passCount: Int,
    val failCount: Int,
    val holdCount: Int,
    val notApplicableCount: Int,
    val records: Map<String, FinancialSafetyRecord>,
)

/**
 * Stage 5 is descriptive only. It is the same market-relative positive trailing-PER
 * band used by scripts/build_final_candidates_v2.py and never changes KR4 ranking.
 */
data class ValuationBand(
    val code: String,
    val labelKo: String,
    val shortLabelKo: String,
    val percentile: Double,
    val changesRank: Boolean = false,
)

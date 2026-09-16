package com.krstock.v3.data.model

/**
 * Standard app-facing representation for the KR4 final research shortlist.
 * This is a research-priority record, not a buy/sell recommendation.
 */
data class CandidateMetricSnapshot(
    val id: String,
    val rawValue: Double,
    val percentileScore: Double,
    val basis: String,
    val asOfDate: String,
    val source: String = ""
)

data class FinalCandidateRecord(
    val schemaVersion: String,
    val policyVersion: String,
    val candidateRank: Int,
    val sourceRank: Int,
    val issuerId: String,
    val name: String,
    val market: String,
    val sector: String,
    val compositeScore: Double,
    val financialSnapshotDate: String,
    val priceCutoffDate: String,
    val m01: CandidateMetricSnapshot,
    val m02: CandidateMetricSnapshot,
    val m03: CandidateMetricSnapshot,
    val m04: CandidateMetricSnapshot,
    val selectionFlags: List<String>,
    val researchStatus: String,
    val selectionReasonKo: String,
    val financialSafetyStatus: String = "UNVERIFIED",
    val financialSafetyReason: String = "",
    val financialSafetyBasis: String = "",
    val debtToEquityPct: Double? = null,
    val currentRatioPct: Double? = null,
    val accountingIdentityGapPct: Double? = null,
    val valuationBand: String = "UNAVAILABLE",
    val valuationBandKo: String = "",
    val valuationPercentile: Double? = null,
)

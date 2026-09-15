package com.krstock.v3.data.model

enum class EvidenceGrade {
    PRIMARY_CONFIRMED,
    STRONGLY_SUPPORTED,
    PLAUSIBLE,
    UNVERIFIED
}

enum class FilingChangeType {
    NEW,
    REMOVED,
    CHANGED,
    STABLE
}

data class PrimarySourceExcerpt(
    val receiptNo: String,
    val reportTitle: String,
    val publishedAt: String,
    val topic: String,
    val excerpt: String,
    val sourceUrl: String
)

data class FilingTopicChange(
    val topic: String,
    val type: FilingChangeType,
    val currentExcerpt: String? = null,
    val previousExcerpt: String? = null
)

data class IndustryKpiCheck(
    val name: String,
    val whyItMatters: String,
    val foundInCurrentFiling: Boolean,
    val excerpt: String? = null
)

data class ResearchForensics(
    val issuerId: String,
    val loaded: Boolean = false,
    val grade: EvidenceGrade = EvidenceGrade.UNVERIFIED,
    val gradeReason: String = "1차 자료 검증 전",
    val rootCauseRead: String = "DART 원문 확인 전에는 원인을 확정하지 않습니다.",
    val currentFilingTitle: String = "",
    val currentFilingDate: String = "",
    val previousFilingTitle: String = "",
    val previousFilingDate: String = "",
    val primaryFacts: List<PrimarySourceExcerpt> = emptyList(),
    val filingChanges: List<FilingTopicChange> = emptyList(),
    val industryKpis: List<IndustryKpiCheck> = emptyList(),
    val alternativeHypotheses: List<String> = emptyList(),
    val nextChecks: List<String> = emptyList(),
    val sourceError: String? = null
) {
    val hasPrimarySource: Boolean
        get() = primaryFacts.isNotEmpty()
}

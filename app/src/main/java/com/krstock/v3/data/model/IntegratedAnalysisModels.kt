package com.krstock.v3.data.model

enum class EvidenceKind {
    NEWS,
    DISCLOSURE,
    IR,
    OFFICIAL
}

enum class EvidenceSourceTier(val priority: Int) {
    DART_PRIMARY(1),
    COMPANY_IR(2),
    COMPANY_OFFICIAL(3),
    TRUSTED_MEDIA(4),
    OTHER(5)
}

data class ContextEvidence(
    val id: String,
    val kind: EvidenceKind,
    val title: String,
    val source: String,
    val publishedAt: String,
    val url: String = "",
    val sourceTier: EvidenceSourceTier = when (kind) {
        EvidenceKind.DISCLOSURE -> EvidenceSourceTier.DART_PRIMARY
        EvidenceKind.IR -> EvidenceSourceTier.COMPANY_IR
        EvidenceKind.OFFICIAL -> EvidenceSourceTier.COMPANY_OFFICIAL
        EvidenceKind.NEWS -> EvidenceSourceTier.TRUSTED_MEDIA
    },
    val receiptNo: String = "",
    val bodyText: String = ""
)

data class DisclosureDiff(
    val available: Boolean = false,
    val scope: String = "",
    val currentTitle: String = "",
    val previousTitle: String = "",
    val newlyAppeared: List<String> = emptyList(),
    val disappeared: List<String> = emptyList(),
    val strengthened: List<String> = emptyList(),
    val weakened: List<String> = emptyList(),
    val note: String = ""
)

data class EvidenceBundle(
    val issuerId: String,
    val news: List<ContextEvidence> = emptyList(),
    val disclosures: List<ContextEvidence> = emptyList(),
    val ir: List<ContextEvidence> = emptyList(),
    val official: List<ContextEvidence> = emptyList(),
    val disclosureDiff: DisclosureDiff? = null,
    val loaded: Boolean = false,
    val error: String? = null
) {
    val all: List<ContextEvidence>
        get() = (disclosures + ir + official + news)
            .distinctBy { "${it.kind}:${it.id}:${it.title}" }
            .sortedWith(
                compareBy<ContextEvidence> { it.sourceTier.priority }
                    .thenByDescending { it.publishedAt }
            )

    val primarySourceCount: Int
        get() = all.count { it.sourceTier.priority <= EvidenceSourceTier.COMPANY_OFFICIAL.priority }
}

data class IntegratedAnalysis(
    val regimeTitle: String,
    val thesis: String,
    val industryContext: String,
    val combinationMeaning: String,
    val causeInvestigation: String,
    val consequence: String,
    val falsifiers: List<String>,
    val confidenceNote: String,
    val evidenceHighlights: List<ContextEvidence> = emptyList(),
    val industryKpiGuide: String = "",
    val disclosureDiffNote: String = "",
    val causeCandidates: List<String> = emptyList(),
    val nextQuarterWatch: List<String> = emptyList(),
    val businessState: String = "",
    val priceBurden: String = "",
    val causeConfidence: String = "",
    val futureUncertainty: String = ""
)

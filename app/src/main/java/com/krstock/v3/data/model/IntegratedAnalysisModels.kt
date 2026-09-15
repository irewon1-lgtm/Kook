package com.krstock.v3.data.model

enum class EvidenceKind {
    NEWS,
    DISCLOSURE
}

data class ContextEvidence(
    val id: String,
    val kind: EvidenceKind,
    val title: String,
    val source: String,
    val publishedAt: String,
    val url: String = "",
    val receiptNo: String = ""
)

data class EvidenceBundle(
    val issuerId: String,
    val news: List<ContextEvidence> = emptyList(),
    val disclosures: List<ContextEvidence> = emptyList(),
    val loaded: Boolean = false,
    val error: String? = null
) {
    val all: List<ContextEvidence>
        get() = (disclosures + news)
            .distinctBy { "${it.kind}:${it.id}:${it.title}" }
            .sortedByDescending { it.publishedAt }
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
    val evidenceHighlights: List<ContextEvidence> = emptyList()
)

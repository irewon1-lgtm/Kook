package com.krstock.v3.data.model

enum class DataStatus {
    REAL,
    REGISTERED,
    DEMO,
    MISSING,
    WAITING_FOR_AUTH
}

data class MetricValue(
    val id: String,
    val nameKo: String,
    val rawValue: Double?,
    val percentileScore: Double?,
    val unit: String,
    val isAvailable: Boolean,
    val reason: String? = null,
    val description: String = "",
    val interpretation: String = "",
    val caution: String = ""
)

data class StockSummary(
    val issuerId: String,
    val name: String,
    val market: String,
    val sector: String = "기타",
    val listingDate: String = "",
    val isFinancial: Boolean = false,
    val isLossMaking: Boolean = false,
    val m01RevGrowth: MetricValue,
    val m02OpMargin: MetricValue,
    val m03Per: MetricValue,
    val m04Price6m: MetricValue,
    val compositeScore: Double?,
    val rankOrder: Int?,
    val isCompositeComplete: Boolean,
    val asOfDate: String,
    val dataSource: String,
    val status: DataStatus
)

data class CompanyReportSection(
    val title: String,
    val content: String
)

data class CompanyReport(
    val issuerId: String,
    val recentChanges: String,
    val changeReason: String,
    val positiveFactors: List<String>,
    val riskFactors: List<String>,
    val counterArguments: String,
    val nextVerificationConditions: List<String>,
    val updatedAt: String,
    val oneLineView: String = "",
    val quantSummary: String = "",
    val businessQuality: String = "",
    val valuationView: String = "",
    val momentumView: String = "",
    val dataLimitations: String = ""
)

data class NewsItem(
    val id: String,
    val title: String,
    val source: String,
    val publishedAt: String,
    val url: String
)

data class FilingItem(
    val receiptNo: String,
    val title: String,
    val publishedAt: String,
    val isDartOfficial: Boolean = true,
    val url: String
)

data class StockDetail(
    val summary: StockSummary,
    val report: CompanyReport,
    val news: List<NewsItem>,
    val filings: List<FilingItem>
)

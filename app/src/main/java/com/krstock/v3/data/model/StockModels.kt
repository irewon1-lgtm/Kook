package com.krstock.v3.data.model

enum class DataStatus {
    REAL,
    DEMO,
    MISSING,
    WAITING_FOR_AUTH
}

data class MetricValue(
    val id: String,          // M01, M02, M03, M04
    val nameKo: String,      // 매출 증가율, 영업이익률, 실적 PER, 최근 6개월 주가 상승률
    val rawValue: Double?,
    val percentileScore: Double?, // 0.0 ~ 100.0 relative score
    val unit: String,
    val isAvailable: Boolean,
    val reason: String? = null,
    val description: String = "",
    val interpretation: String = "",
    val caution: String = ""
)

data class StockSummary(
    val issuerId: String,        // e.g. "005930"
    val name: String,            // e.g. "삼성전자"
    val market: String,          // KOSPI / KOSDAQ
    val sector: String = "기타",
    val isFinancial: Boolean = false,
    val isLossMaking: Boolean = false,

    // 4 Core Metrics
    val m01RevGrowth: MetricValue,
    val m02OpMargin: MetricValue,
    val m03Per: MetricValue,
    val m04Price6m: MetricValue,

    // Candidate Investigation Priority Rank Score
    val compositeScore: Double?, // Null if any of the 4 is missing
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
    val news: List<NewsItem>,        // Max 3
    val filings: List<FilingItem>    // Max 2
)

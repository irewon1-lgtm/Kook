package com.krstock.v3.data.repository

import com.krstock.v3.data.generated.GeneratedKosdaqMaster
import com.krstock.v3.data.generated.GeneratedKospiMaster
import com.krstock.v3.data.model.*

/**
 * KRX KIND listed-company registration repository for KOSPI + KOSDAQ.
 *
 * Identity fields (code/name/sector/listing date/market) are official registration data.
 * Financial metrics, prices, ranks, news and filings remain unavailable until verified
 * providers are connected. Registration status must never be confused with REAL metrics.
 */
object StockRepository {

    private const val MASTER_SOURCE = "KRX KIND 상장법인목록"

    private fun missingMetric(id: String, name: String, unit: String, market: String): MetricValue {
        val description = when (id) {
            "M01" -> "최근 실적의 매출이 전년 동기보다 얼마나 늘었는지 보는 지표입니다."
            "M02" -> "매출 중 본업의 영업이익으로 남는 비율을 보는 지표입니다."
            "M03" -> "최근 실적 기준 이익 대비 현재 주가 수준을 보는 지표입니다."
            "M04" -> "최근 6개월 동안의 주가 흐름을 보는 지표입니다."
            else -> ""
        }
        val interpretation = when (id) {
            "M01" -> "높을수록 성장 측면에서 유리하지만 기저효과를 같이 확인해야 합니다."
            "M02" -> "높을수록 본업 수익성이 좋은 편이지만 업종별 정상 수준이 다릅니다."
            "M03" -> "같은 업종 안에서 비교하며, 낮다고 무조건 저평가인 것은 아닙니다."
            "M04" -> "높을수록 최근 흐름이 강하지만 과열 여부를 함께 확인해야 합니다."
            else -> ""
        }
        val caution = when (id) {
            "M01" -> "연결/별도 범위와 일회성 매출을 확인해야 합니다."
            "M02" -> "금융업 등 일부 업종은 일반적인 영업이익률 비교가 부적절할 수 있습니다."
            "M03" -> "적자기업은 PER이 계산되지 않을 수 있습니다."
            "M04" -> "수정주가, 분할, 거래정지, 상장기간 부족을 확인해야 합니다."
            else -> ""
        }
        return MetricValue(
            id = id,
            nameKo = name,
            rawValue = null,
            percentileScore = null,
            unit = unit,
            isAvailable = false,
            reason = "$market 실종목 등록 완료 · 4지표 실데이터는 아직 미수집",
            description = description,
            interpretation = interpretation,
            caution = caution
        )
    }

    private fun registeredStock(
        issuer: ListedIssuer,
        market: String,
        snapshotDate: String,
        sourceUrl: String
    ): StockSummary = StockSummary(
        issuerId = issuer.code,
        name = issuer.name,
        market = market,
        sector = issuer.sector.ifBlank { "기타" },
        listingDate = issuer.listingDate,
        isFinancial = issuer.sector.contains("금융") || issuer.sector.contains("보험"),
        isLossMaking = false,
        m01RevGrowth = missingMetric("M01", "매출 증가율", "%", market),
        m02OpMargin = missingMetric("M02", "영업이익률", "%", market),
        m03Per = missingMetric("M03", "실적 기준 PER", "배", market),
        m04Price6m = missingMetric("M04", "최근 6개월 주가 상승률", "%", market),
        compositeScore = null,
        rankOrder = null,
        isCompositeComplete = false,
        asOfDate = snapshotDate,
        dataSource = "$MASTER_SOURCE · $sourceUrl",
        status = DataStatus.REGISTERED
    )

    private val registeredStocks: List<StockSummary> by lazy {
        val kospi = GeneratedKospiMaster.issuers.map {
            registeredStock(it, "KOSPI", GeneratedKospiMaster.snapshotDate, GeneratedKospiMaster.sourceUrl)
        }
        val kosdaq = GeneratedKosdaqMaster.issuers.map {
            registeredStock(it, "KOSDAQ", GeneratedKosdaqMaster.snapshotDate, GeneratedKosdaqMaster.sourceUrl)
        }
        val combined = kospi + kosdaq
        require(combined.map { it.issuerId }.toSet().size == combined.size) {
            "Duplicate KRX issue code detected across KOSPI/KOSDAQ masters"
        }
        combined.sortedWith(compareBy<StockSummary> { it.market }.thenBy { it.issuerId })
    }

    fun getAllStocks(): List<StockSummary> = registeredStocks

    fun registeredCount(): Int = registeredStocks.size
    fun kospiCount(): Int = registeredStocks.count { it.market == "KOSPI" }
    fun kosdaqCount(): Int = registeredStocks.count { it.market == "KOSDAQ" }

    fun masterSnapshotDate(): String = maxOf(GeneratedKospiMaster.snapshotDate, GeneratedKosdaqMaster.snapshotDate)
    fun kospiSnapshotDate(): String = GeneratedKospiMaster.snapshotDate
    fun kosdaqSnapshotDate(): String = GeneratedKosdaqMaster.snapshotDate
    fun kospiSourceUrl(): String = GeneratedKospiMaster.sourceUrl
    fun kosdaqSourceUrl(): String = GeneratedKosdaqMaster.sourceUrl
    fun masterSourceUrl(): String = "KOSPI=${GeneratedKospiMaster.sourceUrl} | KOSDAQ=${GeneratedKosdaqMaster.sourceUrl}"

    fun searchStocks(query: String): List<StockSummary> {
        val q = query.trim()
        if (q.isEmpty()) return registeredStocks
        return registeredStocks.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.issuerId.contains(q, ignoreCase = true) ||
                it.sector.contains(q, ignoreCase = true) ||
                it.market.contains(q, ignoreCase = true)
        }
    }

    fun getStockDetail(issuerId: String): StockDetail? {
        val summary = registeredStocks.find { it.issuerId.equals(issuerId, ignoreCase = true) } ?: return null
        return StockDetail(
            summary = summary,
            report = registeredOnlyReport(summary),
            news = emptyList(),
            filings = emptyList()
        )
    }

    private fun registeredOnlyReport(stock: StockSummary): CompanyReport {
        val listingText = stock.listingDate.takeIf { it.isNotBlank() } ?: "상장일 정보 확인 필요"
        return CompanyReport(
            issuerId = stock.issuerId,
            recentChanges = "현재 단계에서는 KRX KIND ${stock.market} 종목 마스터 등록까지 완료했습니다.",
            changeReason = "종목명·종목코드·시장·업종·상장일을 먼저 정확히 등록한 뒤 재무·주가 자료를 순차 연결하기 위한 단계입니다.",
            positiveFactors = listOf(
                "KRX KIND ${stock.market} 상장법인 목록에서 종목 식별정보가 등록되었습니다.",
                "가짜 수치나 DEMO 수치를 실제 데이터처럼 채우지 않았습니다."
            ),
            riskFactors = listOf(
                "매출 증가율은 아직 미수집입니다.",
                "영업이익률은 아직 미수집입니다.",
                "실적 기준 PER은 아직 미수집입니다.",
                "최근 6개월 주가 상승률은 아직 미수집입니다."
            ),
            counterArguments = "종목이 실제 ${stock.market} 상장사라는 사실과 투자 매력도는 별개의 문제입니다. 현재 상태만으로 우열이나 매수 판단을 내릴 수 없습니다.",
            nextVerificationConditions = listOf(
                "OpenDART 또는 동등한 공식 재무 원천에서 최근 실적 연결",
                "최근 분기 매출과 전년동기 매출의 동일 회계범위 검증",
                "최근 분기 영업이익률 계산 및 업종 예외 규칙 적용",
                "TTM 또는 확정 실적 기준 EPS/PER 계산 규칙 확정",
                "수정주가 기반 최근 6개월 상승률 연결",
                "시장별 비교 코호트 상대점수 계산 후에만 순위 생성"
            ),
            updatedAt = stock.asOfDate,
            oneLineView = "${stock.name}(${stock.issuerId})는 ${stock.market} 실종목 등록 완료, 4지표는 아직 미수집 상태입니다.",
            quantSummary = "현재 4개 정량지표는 모두 미수집이므로 종합점수와 순위를 계산하지 않습니다.",
            businessQuality = "시장: ${stock.market} · 업종: ${stock.sector} · 상장일: $listingText. 사업 품질 평가는 재무·공시 자료 연결 후 진행합니다.",
            valuationView = "실적 기준 PER 미수집. 현재 밸류에이션 판단을 제공하지 않습니다.",
            momentumView = "최근 6개월 수정주가 자료 미수집. 현재 모멘텀 판단을 제공하지 않습니다.",
            dataLimitations = "실종목 식별정보만 KRX KIND에서 등록된 단계입니다. 재무·가격·뉴스·공시·AI 의견은 아직 연결하지 않았습니다."
        )
    }
}

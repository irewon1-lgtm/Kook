package com.krstock.v3.data.repository

import com.krstock.v3.data.generated.GeneratedKosdaqMaster
import com.krstock.v3.data.generated.GeneratedKospiMaster
import com.krstock.v3.data.generated.GeneratedRealQuantSnapshot
import com.krstock.v3.data.model.*
import kotlin.math.round

/**
 * KOSPI + KOSDAQ repository backed by:
 *  - KRX KIND for listing identity
 *  - Financial Supervisory Service OpenDART public bulk financial statements for M01/M02
 *  - Naver Finance public stock JSON for actual EPS / daily closes used by M03/M04
 *
 * Missing values remain null. A stock is ranked only when all four metrics are available.
 */
object StockRepository {

    private const val KIND_SOURCE = "KRX KIND 상장법인목록"
    private const val DART_SOURCE = "금융감독원 OpenDART 재무정보 일괄다운로드"
    private const val NAVER_SOURCE = "네이버증권 공개 주식 데이터"

    private val quantByCode: Map<String, RealQuantRecord> by lazy {
        require(GeneratedRealQuantSnapshot.snapshotDate != "PENDING") { "Real quant snapshot has not been generated" }
        require(GeneratedRealQuantSnapshot.universeCount == 2649) {
            "Unexpected real quant universe: ${GeneratedRealQuantSnapshot.universeCount}"
        }
        require(GeneratedRealQuantSnapshot.rows.size == GeneratedRealQuantSnapshot.universeCount) {
            "Real quant row count does not match universeCount"
        }
        val map = GeneratedRealQuantSnapshot.rows.associateBy { it.code }
        require(map.size == GeneratedRealQuantSnapshot.rows.size) { "Duplicate code in real quant snapshot" }
        map
    }

    private data class MetricEducation(
        val description: String,
        val interpretation: String,
        val caution: String
    )

    private fun education(id: String): MetricEducation = when (id) {
        "M01" -> MetricEducation(
            "최근 분기(우선) 또는 반기 누적 매출이 전년 같은 기간보다 얼마나 변했는지 봅니다.",
            "높을수록 성장 측면에서 유리하지만 기저효과와 인수·매각 영향을 함께 봐야 합니다.",
            "OpenDART의 연결재무제표를 우선하며 연결이 없을 때 별도재무제표를 사용합니다."
        )
        "M02" -> MetricEducation(
            "최근 분기(우선) 또는 반기 누적 매출 중 영업이익으로 남는 비율입니다.",
            "높을수록 본업 수익성이 좋은 편이지만 업종별 정상 수준이 크게 다릅니다.",
            "은행·보험·증권 등 금융업은 일반 제조·서비스업식 영업이익률 비교가 부적절해 보류합니다."
        )
        "M03" -> MetricEducation(
            "마지막 완료 거래일 종가를 네이버의 최근 실제 EPS로 나눈 실적 기준 PER입니다.",
            "같은 시장 전체에서 낮은 PER일수록 상대점수를 높게 주지만, 낮은 PER이 곧 저평가를 뜻하지는 않습니다.",
            "EPS가 0 이하인 적자기업은 PER을 만들지 않습니다. 추정 EPS는 사용하지 않습니다."
        )
        "M04" -> MetricEducation(
            "마지막 완료 거래일 종가와 약 6개월 전 마지막 거래일 종가의 등락률입니다.",
            "높을수록 최근 가격 흐름이 강하지만 과열·급등 여부를 별도로 확인해야 합니다.",
            "상장 6개월 미만 등 충분한 가격 이력이 없는 종목은 값을 만들지 않습니다."
        )
        else -> MetricEducation("", "", "")
    }

    private fun reasonKo(code: String?): String = when (code) {
        null, "" -> "검증 가능한 값이 없어 보류"
        "DART_NO_REVENUE" -> "OpenDART 손익계산서에서 비교 가능한 매출 계정을 확인하지 못함"
        "DART_NO_COMPARABLE_PRIOR_REVENUE" -> "전년 동기간과 동일 기준으로 비교할 매출 값이 부족함"
        "DART_REVENUE_GROWTH_OUTLIER_GUARD" -> "매출 증가율이 비정상 범위여서 자동 보류"
        "FINANCIAL_SECTOR_EXCLUDED" -> "금융·보험 업종은 일반 영업이익률 비교에서 제외"
        "DART_NO_OPERATING_INCOME" -> "OpenDART 손익계산서에서 영업이익 계정을 확인하지 못함"
        "DART_NO_COMPARABLE_OPERATING_MARGIN" -> "같은 기간의 매출·영업이익 조합이 부족함"
        "DART_OPERATING_MARGIN_OUTLIER_GUARD" -> "영업이익률이 비정상 범위여서 자동 보류"
        "NONPOSITIVE_EPS" -> "최근 실제 EPS가 0 이하라 PER 계산 불가"
        "NAVER_EPS_MISSING" -> "네이버증권에서 최근 실제 EPS를 확인하지 못함"
        "NAVER_INTEGRATION_ERROR" -> "네이버증권 밸류에이션 자료 호출 실패"
        "NAVER_NO_PRICE_AT_CUTOFF" -> "기준일 이전 유효 종가를 확인하지 못함"
        "NAVER_PER_OUTLIER_GUARD" -> "PER이 비정상 범위여서 자동 보류"
        "NAVER_PRICE_ERROR" -> "네이버증권 가격 이력 호출 실패"
        "NAVER_PRICE_MISSING" -> "유효한 종가를 확인하지 못함"
        "PRICE_HISTORY_SHORTER_THAN_6M" -> "상장기간 등으로 6개월 가격 이력이 부족함"
        "NAVER_RETURN_OUTLIER_GUARD" -> "6개월 수익률이 비정상 범위여서 자동 보류"
        "NAVER_WORKER_EXCEPTION" -> "가격/밸류에이션 수집 중 예외가 발생해 보류"
        "SOURCE_MISSING" -> "원천 자료가 없어 보류"
        else -> "검증 보류: $code"
    }

    private fun metric(
        id: String,
        name: String,
        unit: String,
        raw: Double?,
        percentile: Double?,
        reasonCode: String?,
        basis: String,
        source: String,
        asOfDate: String
    ): MetricValue {
        val edu = education(id)
        val available = raw != null && percentile != null
        return MetricValue(
            id = id,
            nameKo = name,
            rawValue = raw,
            percentileScore = percentile,
            unit = unit,
            isAvailable = available,
            reason = if (available) null else reasonKo(reasonCode),
            description = edu.description,
            interpretation = edu.interpretation,
            caution = edu.caution,
            source = source,
            basis = basis,
            asOfDate = asOfDate
        )
    }

    private fun realStock(
        issuer: ListedIssuer,
        market: String,
        identitySnapshotDate: String,
        sourceUrl: String
    ): StockSummary {
        val q = quantByCode[issuer.code]
            ?: error("Real quant snapshot missing registered issue code ${issuer.code}")

        val m01 = metric(
            "M01", "매출 증가율", "%", q.m01Raw, q.m01Percentile, q.m01ReasonCode, q.m01Basis,
            "$DART_SOURCE · ${GeneratedRealQuantSnapshot.dartFileName}", GeneratedRealQuantSnapshot.snapshotDate
        )
        val m02 = metric(
            "M02", "영업이익률", "%", q.m02Raw, q.m02Percentile, q.m02ReasonCode, q.m02Basis,
            "$DART_SOURCE · ${GeneratedRealQuantSnapshot.dartFileName}", GeneratedRealQuantSnapshot.snapshotDate
        )
        val m03 = metric(
            "M03", "실적 기준 PER", "배", q.m03Raw, q.m03Percentile, q.m03ReasonCode, q.m03Basis,
            NAVER_SOURCE, GeneratedRealQuantSnapshot.priceCutoffDate
        )
        val m04 = metric(
            "M04", "최근 6개월 주가 상승률", "%", q.m04Raw, q.m04Percentile, q.m04ReasonCode, q.m04Basis,
            NAVER_SOURCE, GeneratedRealQuantSnapshot.priceCutoffDate
        )
        val metrics = listOf(m01, m02, m03, m04)
        val allAvailable = metrics.all { it.isAvailable }
        val hasComposite = q.compositeScore != null || q.rankOrder != null
        require((q.compositeScore != null) == (q.rankOrder != null)) {
            "Composite/rank mismatch for ${issuer.code}"
        }
        require(hasComposite == allAvailable) {
            "Complete-score state does not match metric availability for ${issuer.code}"
        }
        val availableCount = metrics.count { it.isAvailable }
        return StockSummary(
            issuerId = issuer.code,
            name = issuer.name,
            market = market,
            sector = issuer.sector.ifBlank { "기타" },
            listingDate = issuer.listingDate,
            isFinancial = issuer.sector.contains("금융") || issuer.sector.contains("보험") || issuer.sector.contains("은행") || issuer.sector.contains("증권"),
            isLossMaking = q.m03ReasonCode == "NONPOSITIVE_EPS",
            m01RevGrowth = m01,
            m02OpMargin = m02,
            m03Per = m03,
            m04Price6m = m04,
            compositeScore = q.compositeScore,
            rankOrder = q.rankOrder,
            isCompositeComplete = allAvailable,
            asOfDate = GeneratedRealQuantSnapshot.snapshotDate,
            dataSource = "$KIND_SOURCE · $sourceUrl | $DART_SOURCE | $NAVER_SOURCE",
            status = if (availableCount > 0) DataStatus.REAL else DataStatus.REGISTERED
        )
    }

    private val registeredStocks: List<StockSummary> by lazy {
        val identities = buildList {
            addAll(GeneratedKospiMaster.issuers.map { Triple(it, "KOSPI", GeneratedKospiMaster.sourceUrl) })
            addAll(GeneratedKosdaqMaster.issuers.map { Triple(it, "KOSDAQ", GeneratedKosdaqMaster.sourceUrl) })
        }
        require(identities.size == 2649) { "Unexpected registered identity universe ${identities.size}" }
        val identityCodes = identities.map { it.first.code }.toSet()
        require(identityCodes.size == identities.size) { "Duplicate KRX issue code in registered masters" }
        require(quantByCode.keys == identityCodes) { "Real quant snapshot identity set differs from KRX master" }

        identities.map { (issuer, market, sourceUrl) ->
            val identityDate = if (market == "KOSPI") GeneratedKospiMaster.snapshotDate else GeneratedKosdaqMaster.snapshotDate
            realStock(issuer, market, identityDate, sourceUrl)
        }.sortedWith(
            compareBy<StockSummary> { it.rankOrder == null }
                .thenBy { it.rankOrder ?: Int.MAX_VALUE }
                .thenBy { it.market }
                .thenBy { it.issuerId }
        )
    }

    fun getAllStocks(): List<StockSummary> = registeredStocks
    fun registeredCount(): Int = registeredStocks.size
    fun kospiCount(): Int = registeredStocks.count { it.market == "KOSPI" }
    fun kosdaqCount(): Int = registeredStocks.count { it.market == "KOSDAQ" }
    fun realDataCount(): Int = registeredStocks.count { it.status == DataStatus.REAL }
    fun completeCount(): Int = registeredStocks.count { it.isCompositeComplete }
    fun masterSnapshotDate(): String = maxOf(GeneratedKospiMaster.snapshotDate, GeneratedKosdaqMaster.snapshotDate)
    fun quantSnapshotDate(): String = GeneratedRealQuantSnapshot.snapshotDate
    fun priceCutoffDate(): String = GeneratedRealQuantSnapshot.priceCutoffDate
    fun dartFileName(): String = GeneratedRealQuantSnapshot.dartFileName
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
            report = realDataReport(summary),
            news = emptyList(),
            filings = emptyList()
        )
    }

    private fun rawText(metric: MetricValue): String {
        if (!metric.isAvailable || metric.rawValue == null || metric.percentileScore == null) {
            return "보류 (${metric.reason ?: "자료 부족"})"
        }
        val raw = when (metric.id) {
            "M03" -> String.format("%.2f배", metric.rawValue)
            else -> String.format("%.1f%%", metric.rawValue)
        }
        return "$raw · 상대 ${String.format("%.1f", metric.percentileScore)}점"
    }

    private fun growthView(metric: MetricValue): String {
        if (!metric.isAvailable || metric.rawValue == null) return "매출 증가율: ${metric.reason ?: "보류"}"
        val view = when {
            metric.rawValue >= 20 -> "매출이 전년 동기간 대비 빠르게 증가"
            metric.rawValue >= 5 -> "매출이 전년 동기간 대비 증가"
            metric.rawValue > -5 -> "매출이 전년 동기간과 비슷한 범위"
            else -> "매출이 전년 동기간 대비 감소"
        }
        return "$view (${String.format("%.1f%%", metric.rawValue)})."
    }

    private fun marginView(metric: MetricValue): String {
        if (!metric.isAvailable || metric.rawValue == null || metric.percentileScore == null) {
            return "영업이익률: ${metric.reason ?: "보류"}"
        }
        val relative = when {
            metric.percentileScore >= 75 -> "전체 비교군 상위권"
            metric.percentileScore >= 50 -> "전체 비교군 중상위권"
            metric.percentileScore >= 25 -> "전체 비교군 중하위권"
            else -> "전체 비교군 하위권"
        }
        return "영업이익률 ${String.format("%.1f%%", metric.rawValue)} · $relative."
    }

    private fun valuationView(metric: MetricValue): String {
        if (!metric.isAvailable || metric.rawValue == null || metric.percentileScore == null) {
            return "실적 기준 PER: ${metric.reason ?: "보류"}"
        }
        val relative = when {
            metric.percentileScore >= 75 -> "PER이 비교군에서 상대적으로 낮은 편"
            metric.percentileScore >= 50 -> "PER이 비교군 중간보다 낮은 편"
            metric.percentileScore >= 25 -> "PER이 비교군 중간보다 높은 편"
            else -> "PER이 비교군에서 상대적으로 높은 편"
        }
        return "실적 PER ${String.format("%.2f배", metric.rawValue)} · $relative."
    }

    private fun momentumView(metric: MetricValue): String {
        if (!metric.isAvailable || metric.rawValue == null) return "6개월 주가: ${metric.reason ?: "보류"}"
        val view = when {
            metric.rawValue >= 30 -> "최근 6개월 상승폭이 매우 큰 상태"
            metric.rawValue >= 10 -> "최근 6개월 상승 흐름"
            metric.rawValue > -10 -> "최근 6개월 보합권"
            else -> "최근 6개월 하락 흐름"
        }
        return "$view (${String.format("%.1f%%", metric.rawValue)})."
    }

    private fun realDataReport(stock: StockSummary): CompanyReport {
        val metrics = listOf(stock.m01RevGrowth, stock.m02OpMargin, stock.m03Per, stock.m04Price6m)
        val available = metrics.count { it.isAvailable }
        val favorable = mutableListOf<String>()
        if (stock.m01RevGrowth.rawValue?.let { it > 0 } == true) favorable += "매출이 전년 동기간 대비 증가했습니다."
        if (stock.m02OpMargin.percentileScore?.let { it >= 60 } == true) favorable += "영업이익률이 전체 비교군에서 상대적으로 양호합니다."
        if (stock.m03Per.percentileScore?.let { it >= 60 } == true) favorable += "실적 PER이 전체 비교군 대비 상대적으로 낮은 편입니다."
        if (stock.m04Price6m.rawValue?.let { it > 0 } == true) favorable += "최근 6개월 주가 흐름이 플러스입니다."
        if (favorable.isEmpty()) favorable += "현재 4지표에서 뚜렷한 우호 신호가 확인되지 않았거나 일부 지표가 보류 상태입니다."

        val risks = mutableListOf<String>()
        metrics.filterNot { it.isAvailable }.forEach { risks += "${it.nameKo}: ${it.reason ?: "보류"}" }
        if (stock.m01RevGrowth.rawValue?.let { it < 0 } == true) risks += "매출이 전년 동기간 대비 감소했습니다."
        if (stock.m02OpMargin.percentileScore?.let { it < 25 } == true) risks += "영업이익률이 전체 비교군 하위권입니다."
        if (stock.m03Per.percentileScore?.let { it < 25 } == true) risks += "실적 PER이 전체 비교군에서 높은 편입니다."
        if (stock.m04Price6m.rawValue?.let { it < -10 } == true) risks += "최근 6개월 주가가 두 자릿수 하락했습니다."
        if (risks.isEmpty()) risks += "4지표만으로는 사업·재무의 모든 위험을 포착할 수 없습니다."

        val oneLine = if (stock.isCompositeComplete && stock.rankOrder != null && stock.compositeScore != null) {
            "4지표 모두 확보 · 종합 상대점수 ${String.format("%.1f", stock.compositeScore)}점 · 전체 ${stock.rankOrder}위"
        } else {
            "실데이터 $available/4개 확보 · 결측 지표가 있어 종합점수와 순위는 보류"
        }

        return CompanyReport(
            issuerId = stock.issuerId,
            recentChanges = "2026년 반기 OpenDART 재무자료와 ${GeneratedRealQuantSnapshot.priceCutoffDate}까지의 가격 자료를 연결했습니다.",
            changeReason = "등록 단계의 빈 지표를 실제 원천값으로 교체하고, 결측은 이유코드와 함께 그대로 보류하도록 변경했습니다.",
            positiveFactors = favorable,
            riskFactors = risks,
            counterArguments = "이 순위는 4개 정량지표의 상대점수를 동일가중 평균한 조사 우선순위입니다. 기업가치, 산업 전망, 지배구조, 공시 이벤트를 모두 반영한 매수·매도 신호가 아닙니다.",
            nextVerificationConditions = listOf(
                "다음 분기 OpenDART 손익계산서가 나오면 M01/M02 갱신",
                "EPS 기준기간이 바뀌면 M03 재계산",
                "매 거래일 종료 후 M03/M04 가격 기준일 갱신",
                "연결/별도 재무제표 범위 변경 감시",
                "분할·합병·거래정지 등 가격 연속성 이벤트 확인",
                "뉴스·공시·사업 품질 분석은 별도 단계에서 추가"
            ),
            updatedAt = stock.asOfDate,
            oneLineView = oneLine,
            quantSummary = "매출 ${rawText(stock.m01RevGrowth)} | 영업마진 ${rawText(stock.m02OpMargin)} | PER ${rawText(stock.m03Per)} | 6개월 ${rawText(stock.m04Price6m)}",
            businessQuality = "${growthView(stock.m01RevGrowth)} ${marginView(stock.m02OpMargin)}",
            valuationView = valuationView(stock.m03Per),
            momentumView = momentumView(stock.m04Price6m),
            dataLimitations = "M01/M02는 OpenDART 2026 반기 손익계산서, M03/M04는 네이버증권 가격·실제 EPS를 사용합니다. 금융업 영업이익률과 적자기업 PER, 상장 6개월 미만 가격은 억지로 계산하지 않습니다. 모든 상대점수는 현재 확보 가능한 종목끼리의 위치이며 투자 권유가 아닙니다."
        )
    }
}

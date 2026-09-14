package com.krstock.v3.data.repository

import com.krstock.v3.data.model.*

/**
 * Bundled repository used by the APK when no live provider is connected.
 *
 * IMPORTANT:
 * - Every bundled record is DEMO. It must never be presented as verified live market data.
 * - Composite scores and ranks are derived in code; they are not manually typed.
 * - Unknown issuer IDs return null instead of silently falling back to another company.
 *
 * Live OpenDART/KRX/provider ingestion belongs in a provider layer and may promote a record
 * to REAL only after provenance, as-of time and completeness checks succeed.
 */
object StockRepository {

    private const val DEMO_SOURCE = "내장 DEMO 샘플 — 실데이터 제공자 미연결"
    private const val DEMO_AS_OF = "DEMO"

    private fun metric(
        id: String,
        name: String,
        raw: Double?,
        percentile: Double?,
        unit: String,
        reason: String? = null
    ): MetricValue {
        val available = raw != null && percentile != null
        val description = when (id) {
            "M01" -> "최근 실적의 매출 성장 정도를 보는 지표입니다. 회사의 외형이 커지는지 확인합니다."
            "M02" -> "매출 중 본업의 영업이익으로 남는 비율입니다. 본업의 수익성과 비용 통제력을 봅니다."
            "M03" -> "주가가 최근 실적 기준 이익의 몇 배에 거래되는지 봅니다. 같은 업종 안에서 비교해야 합니다."
            "M04" -> "최근 6개월 동안 주가 흐름이 얼마나 강했는지 봅니다. 추세 확인용이지 미래 수익률 보장은 아닙니다."
            else -> ""
        }
        val interpretation = when (id) {
            "M01" -> "높을수록 성장 측면에서 유리합니다. 단, 일회성 기저효과인지 같이 확인해야 합니다."
            "M02" -> "높을수록 본업 수익성이 좋습니다. 업종별 정상 마진 차이를 고려해야 합니다."
            "M03" -> "일반적으로 낮을수록 저평가 가능성이 있지만, 성장 둔화·일회성 이익 때문에 낮아질 수도 있습니다."
            "M04" -> "높을수록 최근 시장의 평가가 강합니다. 과열 구간에서는 오히려 변동성 위험이 커질 수 있습니다."
            else -> ""
        }
        val caution = when (id) {
            "M01" -> "전년 동기 비교 기준, 연결/별도 범위, 일회성 매출을 확인하세요."
            "M02" -> "금융업처럼 영업이익률 비교가 부적절한 업종은 N/A가 정상입니다."
            "M03" -> "적자기업은 PER이 계산되지 않으며 금융업은 별도 밸류에이션 기준이 필요합니다."
            "M04" -> "수정주가 기준·기업분할·거래정지·상장기간 부족 여부를 확인해야 합니다."
            else -> ""
        }
        return MetricValue(
            id = id,
            nameKo = name,
            rawValue = raw,
            percentileScore = percentile,
            unit = unit,
            isAvailable = available,
            reason = if (available) null else reason ?: "자료 부족",
            description = description,
            interpretation = interpretation,
            caution = caution
        )
    }

    private fun buildStock(
        issuerId: String,
        name: String,
        market: String,
        sector: String,
        revenueGrowth: Pair<Double?, Double?>,
        operatingMargin: Pair<Double?, Double?>,
        per: Pair<Double?, Double?>,
        price6m: Pair<Double?, Double?>,
        isFinancial: Boolean = false,
        isLossMaking: Boolean = false,
        opMarginReason: String? = null,
        perReason: String? = null
    ): StockSummary {
        val metrics = listOf(
            metric("M01", "매출 증가율", revenueGrowth.first, revenueGrowth.second, "%"),
            metric("M02", "영업이익률", operatingMargin.first, operatingMargin.second, "%", opMarginReason),
            metric("M03", "실적 기준 PER", per.first, per.second, "배", perReason),
            metric("M04", "최근 6개월 주가 상승률", price6m.first, price6m.second, "%")
        )
        val complete = metrics.all { it.isAvailable }
        val score = if (complete) metrics.mapNotNull { it.percentileScore }.average() else null

        return StockSummary(
            issuerId = issuerId,
            name = name,
            market = market,
            sector = sector,
            isFinancial = isFinancial,
            isLossMaking = isLossMaking,
            m01RevGrowth = metrics[0],
            m02OpMargin = metrics[1],
            m03Per = metrics[2],
            m04Price6m = metrics[3],
            compositeScore = score,
            rankOrder = null,
            isCompositeComplete = complete,
            asOfDate = DEMO_AS_OF,
            dataSource = DEMO_SOURCE,
            status = DataStatus.DEMO
        )
    }

    // Synthetic values for UI/logic validation only. They are deliberately labelled DEMO.
    private val baseStocks = listOf(
        buildStock("005930", "삼성전자", "KOSPI", "반도체/전자", 12.8 to 85.0, 14.5 to 82.0, 13.2 to 78.0, 8.4 to 70.0),
        buildStock("000660", "SK하이닉스", "KOSPI", "반도체", 24.2 to 95.0, 18.0 to 91.0, 10.5 to 88.0, 15.2 to 85.0),
        buildStock("035420", "NAVER", "KOSPI", "인터넷/플랫폼", 8.5 to 60.0, 11.2 to 65.0, 22.0 to 45.0, -2.1 to 35.0),
        buildStock("035720", "카카오", "KOSPI", "인터넷/플랫폼", 6.0 to 48.0, 7.2 to 42.0, 31.0 to 28.0, -8.0 to 22.0),
        buildStock("005380", "현대차", "KOSPI", "자동차", 9.3 to 66.0, 10.8 to 63.0, 7.8 to 94.0, 11.7 to 77.0),
        buildStock("000270", "기아", "KOSPI", "자동차", 10.7 to 73.0, 12.4 to 76.0, 6.9 to 97.0, 9.6 to 72.0),
        buildStock("373220", "LG에너지솔루션", "KOSPI", "2차전지", 4.1 to 38.0, 5.0 to 31.0, 28.0 to 33.0, -4.3 to 29.0),
        buildStock("006400", "삼성SDI", "KOSPI", "2차전지", -3.4 to 24.0, 6.5 to 38.0, 19.0 to 52.0, -11.1 to 18.0),
        buildStock("207940", "삼성바이오로직스", "KOSPI", "바이오", 18.6 to 90.0, 23.0 to 96.0, 48.0 to 18.0, 21.0 to 92.0),
        buildStock("068270", "셀트리온", "KOSPI", "바이오", 14.1 to 87.0, 16.0 to 86.0, 36.0 to 24.0, 7.0 to 62.0),
        buildStock("034020", "두산에너빌리티", "KOSPI", "원전/전력기기", 11.5 to 80.0, 8.8 to 53.0, 25.0 to 39.0, 28.0 to 98.0),
        buildStock("010120", "LS ELECTRIC", "KOSPI", "전력기기", 20.0 to 93.0, 13.0 to 79.0, 17.0 to 61.0, 25.0 to 95.0),
        buildStock("247540", "에코프로비엠", "KOSDAQ", "2차전지 소재", -9.0 to 12.0, -2.0 to 8.0, null to null, -18.0 to 10.0, isLossMaking = true, perReason = "적자 또는 유효 EPS 부족으로 PER N/A"),
        buildStock("086520", "에코프로", "KOSDAQ", "2차전지 지주", -5.0 to 18.0, 2.5 to 20.0, 55.0 to 12.0, -20.0 to 8.0),
        buildStock("055550", "신한지주", "KOSPI", "금융", 4.2 to 40.0, null to null, null to null, 5.1 to 55.0, isFinancial = true, opMarginReason = "금융업은 일반 제조업식 영업이익률 비교에서 제외", perReason = "금융업은 별도 코호트/밸류에이션 필요")
    )

    private val rankedStocks: List<StockSummary> by lazy {
        val rankByIssuer = baseStocks
            .filter { it.isCompositeComplete && it.compositeScore != null }
            .sortedWith(compareByDescending<StockSummary> { it.compositeScore }.thenBy { it.issuerId })
            .mapIndexed { index, stock -> stock.issuerId to (index + 1) }
            .toMap()

        baseStocks
            .map { it.copy(rankOrder = rankByIssuer[it.issuerId]) }
            .sortedWith(
                compareBy<StockSummary> { it.rankOrder == null }
                    .thenBy { it.rankOrder ?: Int.MAX_VALUE }
                    .thenBy { it.issuerId }
            )
    }

    fun getAllStocks(): List<StockSummary> = rankedStocks

    fun searchStocks(query: String): List<StockSummary> {
        val q = query.trim()
        if (q.isEmpty()) return rankedStocks
        return rankedStocks.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.issuerId.contains(q) ||
                it.sector.contains(q, ignoreCase = true) ||
                it.market.contains(q, ignoreCase = true)
        }
    }

    fun getStockDetail(issuerId: String): StockDetail? {
        val summary = rankedStocks.find { it.issuerId == issuerId } ?: return null
        val report = buildReport(summary)

        // Bundled DEMO must not pretend that fabricated current news/filings are real.
        return StockDetail(
            summary = summary,
            report = report,
            news = emptyList(),
            filings = emptyList()
        )
    }

    private fun buildReport(stock: StockSummary): CompanyReport {
        val available = listOf(stock.m01RevGrowth, stock.m02OpMargin, stock.m03Per, stock.m04Price6m)
            .count { it.isAvailable }
        val scoreText = stock.compositeScore?.let { String.format("%.1f점", it) } ?: "산출 불가"
        val growth = stock.m01RevGrowth.rawValue
        val margin = stock.m02OpMargin.rawValue
        val per = stock.m03Per.rawValue
        val momentum = stock.m04Price6m.rawValue

        val positives = mutableListOf<String>()
        val risks = mutableListOf<String>()

        if (growth != null) {
            if (growth >= 10) positives += "DEMO 기준 매출 증가율이 두 자릿수로 성장 신호가 강한 편입니다."
            else if (growth >= 0) positives += "DEMO 기준 매출은 전년 대비 플러스 성장을 유지합니다."
            else risks += "DEMO 기준 매출이 역성장 구간이라 외형 회복 여부 확인이 필요합니다."
        }
        if (margin != null) {
            if (margin >= 10) positives += "DEMO 기준 영업이익률이 두 자릿수로 본업 수익성이 양호한 편입니다."
            if (margin < 5) risks += "DEMO 기준 영업이익률이 낮아 비용 상승 시 이익 변동성이 커질 수 있습니다."
        } else {
            risks += stock.m02OpMargin.reason ?: "영업이익률 비교 자료가 없습니다."
        }
        if (per != null) {
            if (stock.m03Per.percentileScore != null && stock.m03Per.percentileScore >= 70) positives += "동일 DEMO 코호트에서 PER 상대점수가 높은 편이라 가격 부담이 상대적으로 낮게 평가됩니다."
            if (stock.m03Per.percentileScore != null && stock.m03Per.percentileScore < 30) risks += "DEMO 코호트에서 PER 가격 부담이 상대적으로 높은 편입니다."
        } else {
            risks += stock.m03Per.reason ?: "PER을 계산할 수 없습니다."
        }
        if (momentum != null) {
            if (momentum >= 10) positives += "DEMO 기준 최근 6개월 주가 흐름이 강해 시장 모멘텀이 뒷받침됩니다."
            if (momentum < 0) risks += "DEMO 기준 최근 6개월 주가 흐름이 음수라 시장 평가 약화 여부를 확인해야 합니다."
        }
        if (positives.isEmpty()) positives += "4지표 중 뚜렷한 강점은 아직 확인되지 않았습니다."
        if (risks.isEmpty()) risks += "정량지표가 좋아도 업황·지배구조·현금흐름·일회성 이익 위험은 별도 검증해야 합니다."

        val oneLine = when {
            !stock.isCompositeComplete -> "4지표 중 결측치가 있어 순위보다 결측 원인 확인이 먼저입니다."
            (stock.compositeScore ?: 0.0) >= 75 -> "4지표 DEMO 상대평가상 우선 조사 가치가 높은 후보입니다."
            (stock.compositeScore ?: 0.0) >= 55 -> "정량 강점과 약점이 섞인 중간권 후보로 추가 검증이 필요합니다."
            else -> "현재 DEMO 정량값만으로는 우선 조사 순위가 낮은 편입니다."
        }

        return CompanyReport(
            issuerId = stock.issuerId,
            recentChanges = "현재 APK에는 실시간 수집기가 연결되지 않았습니다. 따라서 '${stock.name}' 화면의 숫자는 UI·계산 검증용 DEMO 데이터이며 실제 최근 실적 변화로 해석하면 안 됩니다.",
            changeReason = "실제 변화 이유는 OpenDART 원문, 회사 IR, 거래소 가격 데이터가 연결된 뒤 근거와 함께 생성해야 합니다. 현재는 근거 없는 원인 추정을 하지 않습니다.",
            positiveFactors = positives,
            riskFactors = risks,
            counterArguments = "정량 점수가 높아도 업종 사이클 정점, 일회성 이익, 회계 기저효과, 과도한 주가 선반영이 있으면 투자 성과로 이어지지 않을 수 있습니다.",
            nextVerificationConditions = listOf(
                "OpenDART 최신 분기/사업보고서에서 매출·영업이익 기준과 연결/별도 범위를 확인",
                "실적 기준 EPS와 현재 주가 기준일을 맞춰 PER 재계산",
                "수정주가 기준 6개월 수익률과 기업행사(분할·증자·거래정지) 확인",
                "4개 지표가 같은 기준일 스냅샷에서 계산됐는지 확인",
                "뉴스·공시 원문을 읽고 정량 변화의 실제 원인을 재검증"
            ),
            updatedAt = "DEMO",
            oneLineView = oneLine,
            quantSummary = "사용 가능 지표 $available/4개 · 종합점수 $scoreText · 순위 ${stock.rankOrder?.let { "${it}위" } ?: "미산출"}",
            businessQuality = when {
                margin == null -> "업종 특성 또는 자료 부족으로 영업이익률 기반 본업 품질 평가는 보류합니다."
                margin >= 15 -> "DEMO 기준 본업 수익성이 높은 편입니다. 실제로는 마진의 지속성과 현금전환을 추가 확인해야 합니다."
                margin >= 8 -> "DEMO 기준 본업 수익성은 중간 이상입니다. 업종 평균과 추세 비교가 필요합니다."
                else -> "DEMO 기준 본업 마진이 낮은 편이므로 원가·판관비·고정비 부담을 점검해야 합니다."
            },
            valuationView = when {
                per == null -> "PER 산출이 부적절하거나 불가능합니다. 대체 가치지표가 필요합니다."
                stock.m03Per.percentileScore != null && stock.m03Per.percentileScore >= 70 -> "DEMO 코호트 안에서는 PER 부담이 상대적으로 낮은 편입니다. 저평가 함정 여부를 확인해야 합니다."
                stock.m03Per.percentileScore != null && stock.m03Per.percentileScore < 30 -> "DEMO 코호트 안에서는 PER 부담이 상대적으로 높은 편입니다. 성장 지속성이 핵심입니다."
                else -> "DEMO 코호트 안에서 PER은 중간권입니다. 성장률과 마진을 함께 봐야 합니다."
            },
            momentumView = when {
                momentum == null -> "6개월 가격 데이터가 없어 모멘텀 판단을 보류합니다."
                momentum >= 20 -> "DEMO 기준 강한 상승 추세입니다. 과열과 변동성 확대를 함께 경계해야 합니다."
                momentum >= 0 -> "DEMO 기준 완만한 플러스 흐름입니다."
                else -> "DEMO 기준 음의 흐름입니다. 하락 원인과 추세 반전 신호를 확인해야 합니다."
            },
            dataLimitations = "이 APK의 내장 데이터는 기능 검증용 DEMO입니다. 실시간 가격·실적·뉴스·공시가 아니며 투자 판단에 사용하면 안 됩니다. REAL 표시는 검증된 제공자 연결 후에만 허용되어야 합니다."
        )
    }
}

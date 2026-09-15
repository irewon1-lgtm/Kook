package com.krstock.v3.data.analysis

import com.krstock.v3.data.evidence.DartPrimaryBundle
import com.krstock.v3.data.model.ContextEvidence
import com.krstock.v3.data.model.EvidenceBundle
import com.krstock.v3.data.model.EvidenceGrade
import com.krstock.v3.data.model.EvidenceKind
import com.krstock.v3.data.model.ResearchForensics
import com.krstock.v3.data.model.StockSummary

object ResearchForensicsEngine {
    private enum class BusinessPattern { QUALITY_GROWTH, REVENUE_FIRST, EFFICIENCY, COMPRESSION, PARTIAL }
    private enum class Signal { DEMAND, INVESTMENT, COST, PRICING_MIX, PRODUCTION, FINANCING, PRODUCT, RISK }

    fun synthesize(
        stock: StockSummary,
        evidence: EvidenceBundle,
        primary: DartPrimaryBundle,
        kpis: List<IndustryKpiDefinition>
    ): ResearchForensics {
        val pattern = pattern(stock)
        val primarySignals = signals(primary.excerpts.map { it.excerpt })
        val secondarySignals = signals(evidence.all.map { it.title })
        val explicitPrimaryCause = primary.excerpts.any { excerpt ->
            CAUSAL_MARKERS.any { excerpt.excerpt.contains(it, ignoreCase = true) }
        }
        val relevant = relevantSignals(pattern)
        val primaryRelevant = primarySignals.intersect(relevant)
        val secondaryRelevant = secondarySignals.intersect(relevant)

        val grade = when {
            primaryRelevant.isNotEmpty() && explicitPrimaryCause -> EvidenceGrade.PRIMARY_CONFIRMED
            primaryRelevant.isNotEmpty() && secondaryRelevant.isNotEmpty() -> EvidenceGrade.STRONGLY_SUPPORTED
            primary.excerpts.isNotEmpty() || secondaryRelevant.isNotEmpty() -> EvidenceGrade.PLAUSIBLE
            else -> EvidenceGrade.UNVERIFIED
        }

        return ResearchForensics(
            issuerId = stock.issuerId,
            loaded = primary.loaded,
            grade = grade,
            gradeReason = gradeReason(grade, primary, evidence, primaryRelevant, secondaryRelevant),
            rootCauseRead = rootCauseRead(pattern, primary, primarySignals, secondarySignals),
            currentFilingTitle = primary.currentTitle,
            currentFilingDate = primary.currentDate,
            previousFilingTitle = primary.previousTitle,
            previousFilingDate = primary.previousDate,
            primaryFacts = primary.excerpts,
            filingChanges = primary.changes,
            industryKpis = primary.kpiChecks,
            alternativeHypotheses = alternatives(pattern),
            nextChecks = nextChecks(pattern, primary, kpis),
            sourceError = primary.error
        )
    }

    private fun pattern(stock: StockSummary): BusinessPattern {
        fun strong(metricId: String): Boolean {
            val metric = when (metricId) {
                "M01" -> stock.m01RevGrowth
                else -> stock.m02OpMargin
            }
            if (!metric.isAvailable) return false
            metric.peerComparison?.betterThanMedian?.let { return it && (metric.percentileScore ?: 50.0) >= 60.0 }
            return (metric.percentileScore ?: 50.0) >= 65.0
        }
        fun weak(metricId: String): Boolean {
            val metric = when (metricId) {
                "M01" -> stock.m01RevGrowth
                else -> stock.m02OpMargin
            }
            if (!metric.isAvailable) return false
            metric.peerComparison?.betterThanMedian?.let { return !it && (metric.percentileScore ?: 50.0) <= 40.0 }
            return (metric.percentileScore ?: 50.0) <= 35.0
        }
        if (!stock.m01RevGrowth.isAvailable || !stock.m02OpMargin.isAvailable) return BusinessPattern.PARTIAL
        return when {
            strong("M01") && strong("M02") -> BusinessPattern.QUALITY_GROWTH
            strong("M01") && weak("M02") -> BusinessPattern.REVENUE_FIRST
            weak("M01") && strong("M02") -> BusinessPattern.EFFICIENCY
            weak("M01") && weak("M02") -> BusinessPattern.COMPRESSION
            else -> BusinessPattern.PARTIAL
        }
    }

    private fun relevantSignals(pattern: BusinessPattern): Set<Signal> = when (pattern) {
        BusinessPattern.QUALITY_GROWTH -> setOf(Signal.DEMAND, Signal.PRICING_MIX, Signal.PRODUCTION, Signal.PRODUCT)
        BusinessPattern.REVENUE_FIRST -> setOf(Signal.INVESTMENT, Signal.COST, Signal.PRICING_MIX, Signal.PRODUCTION)
        BusinessPattern.EFFICIENCY -> setOf(Signal.COST, Signal.PRICING_MIX, Signal.PRODUCTION, Signal.DEMAND)
        BusinessPattern.COMPRESSION -> setOf(Signal.DEMAND, Signal.COST, Signal.PRICING_MIX, Signal.RISK)
        BusinessPattern.PARTIAL -> Signal.entries.toSet()
    }

    private fun signals(texts: List<String>): Set<Signal> {
        val joined = texts.joinToString(" ").lowercase()
        fun has(vararg terms: String) = terms.any { joined.contains(it) }
        return buildSet {
            if (has("수주", "계약", "주문", "고객", "판매량", "출하", "매출증가")) add(Signal.DEMAND)
            if (has("설비투자", "시설투자", "증설", "연구개발", "r&d", "capex", "신규공장")) add(Signal.INVESTMENT)
            if (has("원가", "원재료", "비용", "인건비", "판관비", "운임", "연료비")) add(Signal.COST)
            if (has("판가", "판매가격", "가격인상", "가격인하", "asp", "제품믹스", "고부가", "할인")) add(Signal.PRICING_MIX)
            if (has("가동률", "수율", "생산능력", "생산량", "capa", "양산")) add(Signal.PRODUCTION)
            if (has("유상증자", "전환사채", "cb", "신주", "차입", "자금조달")) add(Signal.FINANCING)
            if (has("신제품", "품목허가", "임상", "승인", "출시", "신규서비스")) add(Signal.PRODUCT)
            if (has("소송", "규제", "계약해지", "계약취소", "리콜", "손상", "충당금", "우발채무")) add(Signal.RISK)
        }
    }

    private fun gradeReason(
        grade: EvidenceGrade,
        primary: DartPrimaryBundle,
        evidence: EvidenceBundle,
        primaryRelevant: Set<Signal>,
        secondaryRelevant: Set<Signal>
    ): String = when (grade) {
        EvidenceGrade.PRIMARY_CONFIRMED ->
            "DART 원문에서 현재 4지표 패턴과 관련된 원인·영향 표현을 직접 확인했습니다. 사실로 확인된 문장과 해석은 화면에서 분리합니다."
        EvidenceGrade.STRONGLY_SUPPORTED ->
            "DART 원문 단서와 최근 뉴스·공시 메타 단서가 같은 방향을 가리킵니다. 다만 숫자 변화 전체를 한 원인으로 단정하지 않습니다."
        EvidenceGrade.PLAUSIBLE -> when {
            primary.excerpts.isNotEmpty() -> "DART 원문에서 관련 단서는 찾았지만 직접적인 원인 문장 또는 독립 교차근거가 부족합니다. 가능성 단계로 유지합니다."
            evidence.all.isNotEmpty() -> "최근 제목 단서는 있지만 DART 본문에서 직접 확인하지 못했습니다. 제목만으로 인과관계를 확정하지 않습니다."
            else -> "관련 근거가 부족해 원인 판단을 보류합니다."
        }
        EvidenceGrade.UNVERIFIED ->
            "1차 자료에서 현재 패턴을 설명할 근거를 확보하지 못했습니다. ${primary.error ?: evidence.error ?: "추측하지 않고 미확인으로 유지합니다."}"
    }

    private fun rootCauseRead(
        pattern: BusinessPattern,
        primary: DartPrimaryBundle,
        primarySignals: Set<Signal>,
        secondarySignals: Set<Signal>
    ): String {
        if (primary.excerpts.isEmpty()) {
            return if (primary.error != null) {
                "DART 원문을 안정적으로 읽지 못해 ‘왜 이런 숫자가 나왔는가’에 대한 단정은 중지했습니다. 최근 뉴스·공시 제목은 가설 후보를 찾는 데만 쓰며 원인 증명으로 사용하지 않습니다."
            } else {
                "현재 확보한 DART 원문에서 원인을 뒷받침할 충분한 문장을 찾지 못했습니다. 숫자 패턴만으로 경영 원인을 만들어내지 않습니다."
            }
        }
        return when (pattern) {
            BusinessPattern.REVENUE_FIRST -> when {
                Signal.INVESTMENT in primarySignals && Signal.PRODUCTION in primarySignals ->
                    "외형은 앞서는데 수익성이 뒤처지는 이유를 확인한 결과, DART 원문에 설비·R&D 같은 선행투자와 생산·가동 관련 단서가 함께 있습니다. 따라서 ‘지금 비용을 먼저 부담하고 이후 생산성과 마진을 회수하는 과정’은 우선 검증할 설명입니다. 다만 원가 상승이나 저마진 수주가 동시에 존재하면 선투자만으로 설명할 수 없으므로 다음 공시에서 가동률·수율·제품 믹스가 실제로 개선되는지 확인해야 합니다."
                Signal.INVESTMENT in primarySignals ->
                    "DART 원문에서 투자·R&D·증설 관련 단서가 확인됩니다. 선투자 가설은 숫자만 보고 만든 추측보다 한 단계 강해졌지만, 아직 그 지출이 마진 하락의 전부라고 볼 수는 없습니다. 투자 이후 생산능력과 고마진 매출이 따라오는지가 핵심 반증 조건입니다."
                Signal.COST in primarySignals ->
                    "DART 원문에서 원가·비용 관련 단서가 확인됩니다. 이 경우 외형 성장보다 비용 전가 능력과 원가 정상화 속도가 더 중요합니다. 다음 기간에 매출이 유지돼도 비용 압박이 줄지 않으면 낮은 수익성이 구조화될 수 있습니다."
                Signal.PRICING_MIX in primarySignals ->
                    "DART 원문에서 가격·제품 믹스 관련 단서가 확인됩니다. 외형 성장이 할인, 낮은 판가 또는 저마진 제품 비중 확대로 만들어졌는지 먼저 구분해야 합니다. 이 가설이 맞다면 매출 성장 자체보다 단가·믹스 개선이 선행돼야 마진이 회복됩니다."
                else ->
                    "DART 원문을 읽었지만 선투자·원가·가격경쟁 중 어느 설명이 우세한지 가를 직접 단서가 부족합니다. 이 상태에서는 매출 성장에 ‘좋은 성장’이라는 라벨을 붙이지 않습니다."
            }
            BusinessPattern.QUALITY_GROWTH -> when {
                Signal.DEMAND in primarySignals && Signal.PRICING_MIX in primarySignals ->
                    "DART 원문에서 수요·수주 단서와 가격·제품 믹스 단서가 함께 확인됩니다. 성장과 수익성이 동시에 강한 숫자가 단순 물량 밀어내기보다 수요와 경제성의 동반 개선에서 나왔는지 확인할 수 있는 조합입니다. 다음 검증은 이 수요가 반복 가능하고 가격·믹스 우위가 유지되는지입니다."
                Signal.DEMAND in primarySignals ->
                    "DART 원문에서 고객·수주·판매량 관련 단서가 확인됩니다. 현재의 질 좋은 성장 패턴을 실제 수요가 지지할 가능성이 높아졌습니다. 다만 높은 마진의 이유까지 확인하려면 가격·믹스·가동률 근거가 추가로 필요합니다."
                else ->
                    "숫자는 질 좋은 성장에 가깝지만 DART 원문에서 이를 설명할 수요·가격·가동 단서를 충분히 찾지 못했습니다. 좋은 결과와 좋은 원인을 같은 것으로 취급하지 않습니다."
            }
            BusinessPattern.EFFICIENCY -> when {
                Signal.COST in primarySignals || Signal.PRICING_MIX in primarySignals ->
                    "외형이 강하지 않은데 수익성이 버티는 이유를 확인한 결과 원가·비용 또는 가격·제품 믹스 관련 단서가 DART 원문에 있습니다. 비용구조 개선으로 체질이 좋아진 것인지, 성장 정체 속에서 단기적으로 이익만 방어한 것인지는 수요 KPI가 다시 살아나는지로 구분해야 합니다."
                else ->
                    "수익성 방어의 직접 원인을 DART 원문에서 충분히 확인하지 못했습니다. 비용 절감이나 고마진 믹스 개선을 사실처럼 쓰지 않고 가설로 남깁니다."
            }
            BusinessPattern.COMPRESSION -> when {
                Signal.DEMAND in primarySignals && Signal.COST in primarySignals ->
                    "DART 원문에 수요 관련 약화 단서와 비용 관련 단서가 함께 있어, 외형과 수익성이 동시에 압박받는 구조를 우선 경계해야 합니다. 이 경우 매출 둔화보다 이익 감소가 더 커지는 역영업레버리지가 나타날 수 있으므로 수요 회복 또는 비용 정상화 중 하나가 먼저 확인돼야 합니다."
                Signal.RISK in primarySignals ->
                    "DART 원문에서 규제·계약·충당금 등 별도 위험 단서가 확인됩니다. 낮아진 숫자를 단순 업황 문제로만 보면 안 되며, 해당 위험이 일회성인지 반복 가능한 구조적 문제인지 우선 확인해야 합니다."
                else ->
                    "외형과 수익성이 같이 약하지만 DART 원문에서 수요·비용 중 주원인을 가를 충분한 근거가 없습니다. 싸 보이는 밸류에이션을 저평가로 해석하기 전에 원인 확인이 먼저입니다."
            }
            BusinessPattern.PARTIAL ->
                "지표가 혼합돼 있어 한 가지 원인으로 압축하지 않았습니다. DART 원문에서 확인된 사실을 먼저 제시하고, 서로 충돌하는 설명 중 어떤 것이 다음 분기 숫자로 검증되는지 추적하는 방식이 적절합니다."
        }
    }

    private fun alternatives(pattern: BusinessPattern): List<String> = when (pattern) {
        BusinessPattern.REVENUE_FIRST -> listOf(
            "선행투자형: 증설·R&D·인력 확대로 현재 비용을 먼저 부담하는 경우",
            "비용압박형: 원재료·인건비·운임·초기 수율 문제로 일시적으로 마진이 눌리는 경우",
            "저마진성장형: 가격 인하·저마진 수주·제품 믹스 악화로 외형만 커지는 경우"
        )
        BusinessPattern.QUALITY_GROWTH -> listOf(
            "실수요 확장형: 고객·판매량·수주가 늘면서 규모의 경제까지 나타나는 경우",
            "가격·믹스 개선형: 고부가 제품과 판가 상승이 매출과 마진을 동시에 끌어올리는 경우",
            "사이클 고점형: 경기민감 업종에서 일시적 가격 상승이 성장과 마진을 동시에 부풀린 경우"
        )
        BusinessPattern.EFFICIENCY -> listOf(
            "비용구조 개선형: 고정비·판관비 절감으로 매출 없이도 마진이 개선되는 경우",
            "고마진 집중형: 저수익 사업을 줄이고 고부가 제품 비중을 높인 경우",
            "성숙·축소형: 성장기회가 줄어 비용을 줄이며 이익만 방어하는 경우"
        )
        BusinessPattern.COMPRESSION -> listOf(
            "수요둔화형: 판매량·수주 감소로 고정비 부담까지 커지는 경우",
            "비용충격형: 원가 상승을 가격에 전가하지 못해 수익성이 더 빠르게 훼손되는 경우",
            "구조적 경쟁력 약화형: 가격경쟁·고객이탈·규제·제품 노후화가 동시에 진행되는 경우"
        )
        BusinessPattern.PARTIAL -> listOf(
            "현재 지표 중 결측 또는 중립 신호가 많아 단일 원인보다 추가 자료 확인이 우선",
            "업종 특수 KPI가 4지표와 다른 방향을 가리킬 가능성",
            "일회성 회계·계약 인식 시점이 최신 숫자를 왜곡했을 가능성"
        )
    }

    private fun nextChecks(
        pattern: BusinessPattern,
        primary: DartPrimaryBundle,
        kpis: List<IndustryKpiDefinition>
    ): List<String> {
        val result = mutableListOf<String>()
        primary.kpiChecks.filterNot { it.foundInCurrentFiling }.take(3).forEach {
            result += "다음 공시에서 '${it.name}'를 직접 확인: ${it.whyItMatters}"
        }
        primary.changes.filter { it.type.name != "STABLE" }.take(2).forEach {
            result += "공시 변화 추적: '${it.topic}' 항목이 다음 보고서에서도 같은 방향으로 바뀌는지 확인"
        }
        if (result.isEmpty()) {
            result += kpis.take(3).map { "다음 분기 '${it.name}' 변화가 현재 해석과 일치하는지 확인" }
        }
        when (pattern) {
            BusinessPattern.REVENUE_FIRST -> result += "매출 증가가 계속될 때 마진이 후행 회복하는지 확인; 회복이 없으면 저마진 성장 가설의 비중을 높임"
            BusinessPattern.QUALITY_GROWTH -> result += "수주·고객·가격·가동률 중 현재 우위를 만든 동인이 다음 분기에도 반복되는지 확인"
            BusinessPattern.EFFICIENCY -> result += "비용절감 뒤 매출 성장까지 재개되는지 확인; 성장 없이 마진만 유지되면 성숙 구간으로 재분류"
            BusinessPattern.COMPRESSION -> result += "수요 회복 또는 비용 정상화 중 어느 신호가 먼저 나타나는지 확인"
            BusinessPattern.PARTIAL -> result += "결측 4지표와 업종 KPI를 보완하기 전 강한 결론을 내리지 않음"
        }
        return result.distinct().take(6)
    }

    private val CAUSAL_MARKERS = listOf(
        "원인", "영향", "때문", "따라", "증가", "감소", "확대", "축소", "상승", "하락", "개선", "악화", "부담", "회복"
    )
}

package com.krstock.v3.data.analysis

import com.krstock.v3.data.model.ContextEvidence
import com.krstock.v3.data.model.DisclosureDiff
import com.krstock.v3.data.model.EvidenceBundle
import com.krstock.v3.data.model.EvidenceKind
import com.krstock.v3.data.model.EvidenceSourceTier
import com.krstock.v3.data.model.IntegratedAnalysis
import com.krstock.v3.data.model.MetricValue
import com.krstock.v3.data.model.StockSummary

/**
 * KR4 deep interpretation engine.
 *
 * The four metrics are anomaly sensors, not the explanation itself. This engine
 * first classifies the four-factor pattern, then asks which industry KPI should
 * explain it, checks primary-source evidence and disclosure changes, keeps
 * competing causal hypotheses alive until evidence can remove them, and finally
 * states the next observable consequence and explicit falsifiers.
 */
object IntegratedInterpretationEngine {
    private enum class Level { VERY_STRONG, STRONG, NEUTRAL, WEAK, VERY_WEAK, UNKNOWN }

    private enum class Regime {
        QUALITY_GROWTH,
        REVENUE_FIRST_GROWTH,
        EFFICIENCY_WITHOUT_GROWTH,
        FUNDAMENTAL_COMPRESSION,
        FINANCIAL_PARTIAL,
        PARTIAL
    }

    private enum class EvidenceSignal {
        ORDER_DEMAND,
        INVESTMENT_RND,
        MARGIN_COST,
        PRICING_MIX,
        EARNINGS,
        FINANCING_DILUTION,
        SHAREHOLDER_RETURN,
        MNA_RESTRUCTURE,
        REGULATION_LEGAL,
        PRODUCT_APPROVAL,
        CONTRACT_RISK,
        MANAGEMENT_OUTLOOK
    }

    private data class TaggedEvidence(
        val evidence: ContextEvidence,
        val signals: Set<EvidenceSignal>
    )

    private data class CauseHypothesis(
        val label: String,
        val explanation: String,
        val supports: Set<EvidenceSignal>,
        val contradicts: Set<EvidenceSignal> = emptySet(),
        val diffWords: Set<String> = emptySet()
    )

    fun analyze(stock: StockSummary, evidence: EvidenceBundle = EvidenceBundle(stock.issuerId)): IntegratedAnalysis {
        val profile = IndustryInsightCatalog.profileFor(stock.sector)
        val kpi = IndustryKpiCatalog.profileFor(stock.sector)
        val growth = level(stock.m01RevGrowth)
        val margin = level(stock.m02OpMargin)
        val valuation = level(stock.m03Per)
        val momentum = level(stock.m04Price6m)
        val regime = regime(stock, growth, margin)
        val tagged = tagEvidence(evidence.all)
        val diff = evidence.disclosureDiff
        val hypotheses = hypotheses(regime)
        val highlights = selectEvidence(regime, tagged).map { it.evidence }.take(6)
        val watch = nextQuarterWatch(regime, kpi)

        val businessState = businessState(regime)
        val priceBurden = priceBurden(valuation, momentum)
        val causeConfidence = causeConfidence(stock, evidence, highlights, diff)
        val uncertainty = futureUncertainty(stock, regime, valuation, momentum, evidence)
        val kpiGuide = industryKpiGuide(kpi)
        val diffNote = disclosureDiffNote(diff)
        val candidateLabels = hypotheses.map { it.label }

        return IntegratedAnalysis(
            regimeTitle = regimeTitle(regime, valuation, momentum),
            thesis = thesis(regime, valuation, momentum),
            industryContext = industryContext(profile, kpi, stock),
            combinationMeaning = combinationMeaning(regime, valuation, momentum, stock),
            causeInvestigation = causeInvestigation(regime, profile, kpi, hypotheses, tagged, evidence, diff),
            consequence = consequence(regime, valuation, momentum, watch),
            falsifiers = falsifiers(regime, profile, kpi, stock),
            confidenceNote = confidenceNote(stock, evidence, highlights, diff, businessState, priceBurden, causeConfidence, uncertainty),
            evidenceHighlights = highlights,
            industryKpiGuide = kpiGuide,
            disclosureDiffNote = diffNote,
            causeCandidates = candidateLabels,
            nextQuarterWatch = watch,
            businessState = businessState,
            priceBurden = priceBurden,
            causeConfidence = causeConfidence,
            futureUncertainty = uncertainty
        )
    }

    private fun level(metric: MetricValue): Level {
        if (!metric.isAvailable || metric.rawValue == null) return Level.UNKNOWN
        val peer = metric.peerComparison
        val market = metric.percentileScore

        if (peer?.isSufficient == true && peer.betterThanMedian != null) {
            return when {
                peer.betterThanMedian == true && (market ?: 50.0) >= 90.0 -> Level.VERY_STRONG
                peer.betterThanMedian == true && (market ?: 50.0) >= 60.0 -> Level.STRONG
                peer.betterThanMedian == true -> Level.NEUTRAL
                peer.betterThanMedian == false && (market ?: 50.0) <= 10.0 -> Level.VERY_WEAK
                peer.betterThanMedian == false && (market ?: 50.0) <= 40.0 -> Level.WEAK
                else -> Level.NEUTRAL
            }
        }

        return when {
            market == null -> Level.NEUTRAL
            market >= 90.0 -> Level.VERY_STRONG
            market >= 65.0 -> Level.STRONG
            market >= 35.0 -> Level.NEUTRAL
            market >= 10.0 -> Level.WEAK
            else -> Level.VERY_WEAK
        }
    }

    private fun isPositive(level: Level): Boolean = level == Level.STRONG || level == Level.VERY_STRONG
    private fun isNegative(level: Level): Boolean = level == Level.WEAK || level == Level.VERY_WEAK

    private fun regime(stock: StockSummary, growth: Level, margin: Level): Regime {
        if (stock.isFinancial && margin == Level.UNKNOWN) return Regime.FINANCIAL_PARTIAL
        if (growth == Level.UNKNOWN || margin == Level.UNKNOWN) return Regime.PARTIAL
        return when {
            isPositive(growth) && isPositive(margin) -> Regime.QUALITY_GROWTH
            isPositive(growth) && isNegative(margin) -> Regime.REVENUE_FIRST_GROWTH
            isNegative(growth) && isPositive(margin) -> Regime.EFFICIENCY_WITHOUT_GROWTH
            isNegative(growth) && isNegative(margin) -> Regime.FUNDAMENTAL_COMPRESSION
            else -> Regime.PARTIAL
        }
    }

    private fun regimeTitle(regime: Regime, valuation: Level, momentum: Level): String {
        val base = when (regime) {
            Regime.QUALITY_GROWTH -> "질 좋은 성장"
            Regime.REVENUE_FIRST_GROWTH -> "매출 선행형 성장"
            Regime.EFFICIENCY_WITHOUT_GROWTH -> "효율 개선형·성숙 구간"
            Regime.FUNDAMENTAL_COMPRESSION -> "성장·수익성 동반 압박"
            Regime.FINANCIAL_PARTIAL -> "금융업 전용지표 보완 필요"
            Regime.PARTIAL -> "혼합 신호·추가 확인 필요"
        }
        val overlay = when {
            isPositive(valuation) && isPositive(momentum) -> " · 가격 부담은 낮고 흐름은 강함"
            isNegative(valuation) && isPositive(momentum) -> " · 기대가 가격에 선반영되는 중"
            isPositive(valuation) && isNegative(momentum) -> " · 싸지만 시장 확인은 아직 약함"
            isNegative(valuation) && isNegative(momentum) -> " · 비싼데 흐름도 약한 취약 조합"
            else -> ""
        }
        return base + overlay
    }

    private fun thesis(regime: Regime, valuation: Level, momentum: Level): String {
        val core = when (regime) {
            Regime.QUALITY_GROWTH ->
                "사업 규모가 커지는 동시에 그 성장이 이익으로도 남는 패턴입니다. 이 조합의 핵심은 ‘많이 파는 것’과 ‘남기는 것’이 같이 좋아진다는 점입니다. 다만 여기서 결론을 끝내지 않습니다. 같은 업종의 핵심 KPI가 실제 수요·가격·가동률 개선을 확인해 주는지, 최근 공시의 설명이 과거보다 강화됐는지까지 이어져야 지속 가능한 질 좋은 성장으로 볼 수 있습니다."
            Regime.REVENUE_FIRST_GROWTH ->
                "매출은 앞서가는데 수익성이 따라오지 않는 패턴입니다. 이 경우 진짜 질문은 ‘성장이 좋은가’가 아니라 ‘왜 성장의 대가로 마진을 포기하고 있는가’입니다. 증설·R&D·영업망 확대 같은 선투자, 신규 라인의 초기 가동비·수율 문제, 가격경쟁이나 저마진 제품 비중 확대는 겉으로 같은 숫자를 만들지만 미래 의미가 정반대라서 반드시 원인을 분해해야 합니다."
            Regime.EFFICIENCY_WITHOUT_GROWTH ->
                "외형은 약하지만 남기는 힘은 버티는 패턴입니다. 비용 절감, 제품 믹스 개선, 가격 인상처럼 운영 효율이 좋아진 것일 수 있고, 반대로 성장이 멈춘 성숙기업이 비용만 줄여 이익을 방어하는 상황일 수도 있습니다. 따라서 현재의 마진 자체보다 개선의 원인이 반복 가능한지, 이후 매출 회복을 받을 수 있는 구조인지가 핵심입니다."
            Regime.FUNDAMENTAL_COMPRESSION ->
                "외형과 수익성이 같이 약해지는 패턴입니다. 수요 둔화와 비용 부담이 동시에 나타날 때 흔하며, 단순히 PER이 낮다는 이유만으로 접근하면 ‘싸 보이는 이유가 있는 주식’이 될 가능성이 큽니다. 회복 판단은 낮아진 가격이 아니라 수주·가격·재고·원가 같은 선행 KPI가 먼저 돌아서는지에서 시작해야 합니다."
            Regime.FINANCIAL_PARTIAL ->
                "현재 네 지표만으로는 금융업의 수익구조를 충분히 설명할 수 없습니다. 금융사는 일반 영업이익률 대신 ROE·NIM·손해율·충당금·자본비율을 봐야 하므로 성장·PER·가격 흐름만 보조적으로 사용하고 결론의 강도를 낮춥니다. 업종 전용 KPI가 채워지기 전에는 좋은 숫자 하나를 전체 사업 상태로 확대해석하지 않습니다."
            Regime.PARTIAL ->
                "네 지표가 한 방향으로 정렬되지 않거나 일부가 비어 있습니다. 이런 경우 한 숫자를 대표값으로 삼지 않고 서로 충돌하는 신호가 무엇인지, 어떤 업종 KPI와 공시 변화가 그 충돌을 풀어줄지를 먼저 봅니다. 가격이 실물보다 먼저 움직였는지, 실물이 가격보다 먼저 움직였는지를 구분하는 구간입니다."
        }

        val overlay = when {
            isNegative(valuation) && isPositive(momentum) ->
                " 여기에 높은 밸류에이션과 강한 주가 흐름이 겹치면 시장이 현재 실적보다 미래 개선을 먼저 사고 있는 상태입니다. 좋은 회사인지보다 ‘이미 좋은 미래를 얼마까지 가격에 넣었는가’가 리스크의 중심이 됩니다."
            isPositive(valuation) && isNegative(momentum) ->
                " 반대로 상대적으로 부담이 낮은 가격에도 주가 흐름이 약하다면 시장이 숫자의 지속성을 믿지 않거나 네 지표 밖의 위험을 반영하고 있을 수 있습니다. 저평가라는 단어보다 반대 근거를 먼저 찾아야 합니다."
            isPositive(valuation) && isPositive(momentum) ->
                " 가격 부담이 상대적으로 낮고 흐름까지 받쳐주면 재평가가 진행되는 조합일 수 있습니다. 다만 경기민감 업종에서는 이익 정점 때문에 싸 보이는 착시인지 먼저 확인합니다."
            isNegative(valuation) && isNegative(momentum) ->
                " 가격 부담이 높은데 흐름까지 약하면 기대가 내려오는 초기 구간일 수 있습니다. 이 조합에서는 ‘좋아질 것’이라는 가정보다 실제 개선 증거가 먼저 필요합니다."
            else -> ""
        }
        return core + overlay
    }

    private fun industryContext(profile: IndustryProfile, kpi: IndustryKpiProfile, stock: StockSummary): String = buildString {
        append("${profile.label}에서 먼저 봐야 할 것은 단순한 성장률의 높고 낮음이 아닙니다. ")
        append(profile.economics)
        append("\n\n성장 숫자를 읽을 때는 ")
        append(profile.growthLens)
        append(" 수익성은 ")
        append(profile.marginLens)
        append(" 밸류에이션은 ")
        append(profile.valuationLens)
        append("\n\n[업종 KPI 원인 센서]\n")
        kpi.kpis.take(5).forEach { item ->
            append("• ${item.name}: ${item.meaning}. ${item.expectedLink}\n")
        }
        append("이 KPI들은 4지표를 대체하지 않습니다. 4지표가 ‘무슨 이상이 있는지’를 찾고, 업종 KPI가 ‘왜 그런지’를 검증합니다.")
        if (stock.sector != profile.label) {
            append("\n\n현재 KRX 세부업종은 ‘${stock.sector}’이고 위 설명은 이를 더 넓은 경제구조로 묶어 읽은 것입니다. 실제 피어 중앙값이 충분하면 넓은 산업 설명보다 세부 피어 비교를 우선합니다.")
        }
    }

    private fun industryKpiGuide(kpi: IndustryKpiProfile): String = buildString {
        append("${kpi.label} 핵심 KPI: ")
        append(kpi.kpis.joinToString(" → ") { it.name })
        append(". 원인 판독 순서는 ")
        append(kpi.nextQuarterChain.joinToString(" → "))
        append(" 순으로 확인합니다.")
    }

    private fun combinationMeaning(
        regime: Regime,
        valuation: Level,
        momentum: Level,
        stock: StockSummary
    ): String {
        val business = when (regime) {
            Regime.QUALITY_GROWTH ->
                "성장과 마진이 같이 강하다는 것은 매출 증가가 할인·저가수주 같은 희생만으로 만들어진 가능성이 낮다는 뜻입니다. 특히 피어보다 마진까지 우위라면 규모 확대가 비용 구조를 망가뜨리지 않고 있다는 신호가 됩니다. 그러나 여기서 ‘좋은 회사’로 결론내리지는 않습니다. 출하량·고객수 같은 물량 KPI, 판가·ARPU 같은 가격 KPI, 가동률·유지율 같은 품질 KPI 중 무엇이 실제로 좋아졌는지 확인해야 성장의 재현성을 판단할 수 있습니다."
            Regime.REVENUE_FIRST_GROWTH ->
                "매출 증가와 마진 약화가 동시에 보이면 사업은 확장 중이지만 경제성은 아직 증명되지 않은 상태입니다. 이 조합은 크게 세 갈래입니다. 첫째, 증설·R&D·영업망 확대처럼 지금 비용을 쓰고 나중에 회수하는 선투자. 둘째, 신규 라인 초기 수율·가동률이나 원가 때문에 일시적으로 마진이 눌린 경우. 셋째, 가격 인하·저마진 수주·낮은 제품 믹스로 외형만 키우는 경우입니다. 세 경우는 같은 4지표를 만들 수 있으므로 업종 KPI와 공시 변화로 원인을 제거해 가야 합니다."
            Regime.EFFICIENCY_WITHOUT_GROWTH ->
                "매출이 약한데 마진이 버티거나 좋아지면 비용구조 개선이나 고마진 제품 집중이 진행될 수 있습니다. 이 패턴은 현금창출을 안정시킬 수 있지만 성장 재개가 없으면 밸류에이션 확장이 제한됩니다. 비용 감소가 일회성인지, 가격·믹스 개선이 반복 가능한지, 신규 고객·수주가 다시 붙는지 순서대로 봐야 하며 ‘이익률이 올랐다’는 한 문장으로 끝내면 안 됩니다."
            Regime.FUNDAMENTAL_COMPRESSION ->
                "매출과 마진의 동반 약화는 단순 비용 문제보다 수요와 가격결정력까지 흔들릴 가능성을 시사합니다. 매출이 줄면서 마진도 떨어지면 고정비 부담이 커지는 역영업레버리지가 나타날 수 있어 이익 감소가 매출 감소보다 더 커질 수 있습니다. 반전은 주가가 아니라 수주·재고·판가·원가·가동률 같은 업종 선행 KPI에서 먼저 확인되어야 합니다."
            Regime.FINANCIAL_PARTIAL ->
                "금융업에서는 일반 영업이익률이 빠져 있기 때문에 네 지표의 조합 자체가 불완전합니다. 낮은 PER이 자산건전성 우려를 반영하는지, 주가 모멘텀이 금리·배당 기대에서 나온 것인지 구분하려면 ROE·NIM·손해율·충당금·자본비율을 먼저 확인해야 합니다."
            Regime.PARTIAL ->
                "혼합 신호는 오히려 중요한 정보입니다. 성장·수익성·가격·밸류에이션이 서로 다른 이야기를 할 때는 시장과 실물 사업의 시계가 어긋난 경우가 많습니다. 현재 숫자를 한 문장으로 단정하기보다 어떤 지표가 선행하고 어떤 지표가 후행하는지, 업종 KPI와 공시가 어느 쪽을 지지하는지 구분해야 합니다."
        }

        val market = when {
            isNegative(valuation) && isPositive(momentum) ->
                "PER 부담이 높은데 주가도 강하면 시장이 ‘앞으로 더 좋아질 것’이라는 가정을 적극적으로 가격에 넣고 있는 상태입니다. 현재 실적이 좋아지는 것만으로 부족하고 좋아지는 속도가 이미 가격에 반영된 기대보다 빨라야 주가가 버틸 수 있습니다."
            isPositive(valuation) && isPositive(momentum) ->
                "상대적으로 부담이 낮은 가격과 강한 흐름이 같이 있으면 실적 개선을 시장이 뒤늦게 인정하는 재평가 구간일 수 있습니다. 다만 사이클 업종에서는 현재 이익이 비정상적으로 높은 정점이라 PER이 낮아진 것인지 반드시 구분해야 합니다."
            isNegative(valuation) && isNegative(momentum) ->
                "밸류에이션이 높은데 흐름이 약하면 기대의 재조정이 진행되는 조합입니다. 좋은 스토리보다 실제 KPI와 이익이 기대를 따라잡는지가 중요해집니다."
            isPositive(valuation) && isNegative(momentum) ->
                "싸 보이는데도 주가가 약하면 시장이 네 지표 밖의 문제를 의심하고 있을 수 있습니다. 일회성 이익, 업황 피크, 자금조달, 규제·소송 같은 반대 근거를 공시에서 확인해야 ‘저평가’와 ‘가치 함정’을 구분할 수 있습니다."
            else ->
                "밸류에이션과 모멘텀이 극단적으로 한쪽으로 정렬되지 않았으므로 사업 지표의 지속성이 가격 판단보다 우선입니다."
        }

        val peerNote = if (listOf(stock.m01RevGrowth, stock.m02OpMargin, stock.m03Per, stock.m04Price6m)
                .count { it.peerComparison?.isSufficient == true } >= 3
        ) {
            "이 해석은 절대 숫자를 읽은 것이 아니라 대부분의 지표를 같은 업종 중앙값과 대조한 뒤 만든 것입니다. 업종 자체가 원래 고마진인지 저마진인지 무시한 채 숫자 크기만 평가하지 않습니다."
        } else {
            "일부 지표는 피어 표본이 부족해 전체시장 상대위치와 절대 방향을 보조적으로 사용했습니다. 피어가 부족한 곳에 가짜 업종 평균을 만들어 넣지 않습니다."
        }
        return "$business\n\n$market\n\n$peerNote"
    }

    private fun hypotheses(regime: Regime): List<CauseHypothesis> = when (regime) {
        Regime.REVENUE_FIRST_GROWTH -> listOf(
            CauseHypothesis(
                "선투자형 성장",
                "증설·R&D·영업망 확대 비용이 매출보다 먼저 발생하고 이후 가동률·고정비 흡수로 회수되는 경우",
                setOf(EvidenceSignal.INVESTMENT_RND, EvidenceSignal.ORDER_DEMAND),
                setOf(EvidenceSignal.CONTRACT_RISK),
                setOf("CAPEX·생산능력", "R&D·임상·허가")
            ),
            CauseHypothesis(
                "초기 가동·원가 부담",
                "신규 라인 수율·가동률·감가상각·원재료 부담이 일시적으로 마진을 누르는 경우",
                setOf(EvidenceSignal.MARGIN_COST, EvidenceSignal.INVESTMENT_RND),
                setOf(EvidenceSignal.PRICING_MIX),
                setOf("원가·수율·비용", "CAPEX·생산능력")
            ),
            CauseHypothesis(
                "저마진 외형 성장",
                "할인·가격경쟁·저마진 수주·낮은 제품 믹스로 매출만 커지는 경우",
                setOf(EvidenceSignal.PRICING_MIX, EvidenceSignal.MARGIN_COST),
                setOf(EvidenceSignal.PRODUCT_APPROVAL),
                setOf("가격·제품믹스")
            )
        )
        Regime.QUALITY_GROWTH -> listOf(
            CauseHypothesis("실수요 확대", "수주·고객·판매량 증가가 성장과 이익을 함께 끌어가는 경우", setOf(EvidenceSignal.ORDER_DEMAND), setOf(EvidenceSignal.CONTRACT_RISK), setOf("수주·고객·수요")),
            CauseHypothesis("가격·믹스 개선", "판가 인상이나 고부가 제품 비중 확대가 매출과 마진을 함께 올리는 경우", setOf(EvidenceSignal.PRICING_MIX), emptySet(), setOf("가격·제품믹스")),
            CauseHypothesis("신제품·신사업 기여", "허가·출시·신사업이 새로운 성장축으로 실적에 기여하는 경우", setOf(EvidenceSignal.PRODUCT_APPROVAL), emptySet(), setOf("신사업·신제품", "R&D·임상·허가"))
        )
        Regime.EFFICIENCY_WITHOUT_GROWTH -> listOf(
            CauseHypothesis("비용구조 개선", "구조조정·생산성 개선으로 외형 정체 속에서도 마진이 회복되는 경우", setOf(EvidenceSignal.MNA_RESTRUCTURE, EvidenceSignal.MARGIN_COST)),
            CauseHypothesis("고마진 믹스 집중", "저마진 매출을 줄이고 가격·제품 믹스를 개선한 경우", setOf(EvidenceSignal.PRICING_MIX)),
            CauseHypothesis("성숙기 방어", "신규 성장동력 없이 비용만 줄여 이익을 방어하는 경우", setOf(EvidenceSignal.SHAREHOLDER_RETURN), setOf(EvidenceSignal.ORDER_DEMAND))
        )
        Regime.FUNDAMENTAL_COMPRESSION -> listOf(
            CauseHypothesis("수요 훼손", "주문·고객·계약이 약해져 매출 기반 자체가 줄어드는 경우", setOf(EvidenceSignal.CONTRACT_RISK), setOf(EvidenceSignal.ORDER_DEMAND), setOf("수주·고객·수요")),
            CauseHypothesis("원가·고정비 압박", "매출 둔화와 동시에 원가·감가·인건비 부담이 커지는 경우", setOf(EvidenceSignal.MARGIN_COST), emptySet(), setOf("원가·수율·비용")),
            CauseHypothesis("재무·외부 충격", "자금조달·규제·소송이 사업 악화를 증폭하는 경우", setOf(EvidenceSignal.FINANCING_DILUTION, EvidenceSignal.REGULATION_LEGAL), emptySet(), setOf("자금조달·희석", "신규 위험·불확실성"))
        )
        else -> listOf(
            CauseHypothesis("실적 방향 확인", "현재 혼합 신호를 다음 실적·수주가 어느 방향으로 정렬시키는지 확인", setOf(EvidenceSignal.EARNINGS, EvidenceSignal.ORDER_DEMAND)),
            CauseHypothesis("선투자 여부", "투자·R&D가 현재 비용과 미래 성장 사이의 시차를 만드는지 확인", setOf(EvidenceSignal.INVESTMENT_RND)),
            CauseHypothesis("가격·비용 변화", "판가·믹스 또는 비용 변화가 숫자 충돌의 원인인지 확인", setOf(EvidenceSignal.PRICING_MIX, EvidenceSignal.MARGIN_COST))
        )
    }

    private fun causeInvestigation(
        regime: Regime,
        profile: IndustryProfile,
        kpi: IndustryKpiProfile,
        hypotheses: List<CauseHypothesis>,
        tagged: List<TaggedEvidence>,
        evidence: EvidenceBundle,
        diff: DisclosureDiff?
    ): String {
        if (!evidence.loaded) {
            return "최근 공시·IR·뉴스 확인이 아직 끝나지 않았습니다. 이 상태에서는 ‘투자를 많이 해서 마진이 낮다’ 같은 원인을 사실처럼 단정하지 않습니다. 4지표가 허용하는 가설만 유지하고 실제 원인은 1차 자료가 도착한 뒤 검증합니다.\n\n우선 확인할 업종 KPI는 ${kpi.kpis.take(4).joinToString(" → ") { it.name }}입니다."
        }
        if (evidence.error != null && tagged.isEmpty()) {
            return "최근 공시·뉴스 원문 목록을 불러오지 못해 원인 검증을 보류했습니다. 4지표만으로는 증설, R&D, 가격경쟁, 일회성 비용 중 어느 것이 원인인지 확정할 수 없습니다. 원인 문장을 지어내지 않고 ${profile.keyDrivers.take(4).joinToString("·")}를 다음 확인 항목으로 남깁니다."
        }

        val activeSignals = tagged.flatMap { it.signals }.toSet()
        val diffSignals = buildSet {
            diff?.newlyAppeared?.let(::addAll)
            diff?.strengthened?.let(::addAll)
        }
        val diffAgainst = buildSet {
            diff?.disappeared?.let(::addAll)
            diff?.weakened?.let(::addAll)
        }

        data class Rated(val h: CauseHypothesis, val score: Int, val support: List<ContextEvidence>, val contra: List<ContextEvidence>)
        val rated = hypotheses.map { h ->
            val support = tagged.filter { it.signals.any(h.supports::contains) }.map { it.evidence }
            val contra = tagged.filter { it.signals.any(h.contradicts::contains) }.map { it.evidence }
            val diffSupport = h.diffWords.count { it in diffSignals }
            val diffContra = h.diffWords.count { it in diffAgainst }
            Rated(h, support.size * 2 + diffSupport * 3 - contra.size * 2 - diffContra * 2, support, contra)
        }.sortedByDescending { it.score }

        val lines = rated.mapIndexed { index, r ->
            val state = when {
                r.score >= 4 -> "지지 근거가 가장 강함"
                r.score > 0 -> "일부 지지 근거 있음"
                r.score < 0 -> "반대 근거가 있어 우선순위 하락"
                else -> "현재 근거 부족"
            }
            val src = r.support.take(2).joinToString(" / ") { item ->
                "${sourceLabel(item)} ‘${item.title.take(46)}’"
            }.ifBlank { "직접 지지 근거 없음" }
            "${index + 1}) ${r.h.label} — $state. ${r.h.explanation}. 근거: $src"
        }

        val strongest = rated.firstOrNull()
        val conclusion = if (strongest != null && strongest.score >= 4) {
            "현재 가장 강한 설명은 ‘${strongest.h.label}’입니다. 다만 이것도 확정 사실이 아니라 현재 확보된 증거가 다른 후보보다 더 많이 지지한다는 뜻입니다. 다음 분기에 해당 가설이 예측한 KPI 결과가 실제로 나와야 설명이 유지됩니다."
        } else {
            "현재는 원인 하나를 확정하지 않습니다. 증거가 후보들을 충분히 갈라놓지 못했기 때문에 여러 원인을 유지하고 다음 공시·업종 KPI로 제거해 가는 것이 맞습니다."
        }

        val diffText = disclosureDiffNote(diff)
        val management = managementShift(diff)
        val priorityNote = if (evidence.primarySourceCount > 0) {
            "근거 우선순위는 DART 공시 → 회사 IR → 회사 공식자료 → 신뢰도 높은 뉴스 순입니다. 현재 1차 자료 ${evidence.primarySourceCount}건을 뉴스보다 먼저 사용했습니다."
        } else {
            "현재 1차 자료가 충분하지 않아 뉴스만으로 원인을 확정하지 않습니다. DART·IR 근거가 없는 해석은 확신도를 낮춥니다."
        }

        return buildString {
            append("[가능한 원인 후보를 먼저 분리]\n")
            append(lines.joinToString("\n"))
            append("\n\n[증거로 남긴 가장 강한 설명]\n")
            append(conclusion)
            append("\n\n[공시 Diff]\n")
            append(diffText)
            append("\n\n[경영진 설명 변화]\n")
            append(management)
            append("\n\n[자료 우선순위]\n")
            append(priorityNote)
            append("\n\n이 단계에서도 ‘확정’이라는 표현은 쓰지 않습니다. 숫자 → 업종 KPI → 공시 변화 → 원문 근거가 같은 방향으로 정렬될 때만 설명의 강도를 올립니다.")
        }
    }

    private fun disclosureDiffNote(diff: DisclosureDiff?): String {
        if (diff == null) return "비교 가능한 정기공시가 아직 없어 공시 Diff를 보류했습니다. 없는 변화를 만들어내지 않습니다."
        val changes = buildList {
            if (diff.newlyAppeared.isNotEmpty()) add("새 등장: ${diff.newlyAppeared.joinToString("·")}")
            if (diff.disappeared.isNotEmpty()) add("사라짐: ${diff.disappeared.joinToString("·")}")
            if (diff.strengthened.isNotEmpty()) add("강화: ${diff.strengthened.joinToString("·")}")
            if (diff.weakened.isNotEmpty()) add("약화: ${diff.weakened.joinToString("·")}")
        }
        val pair = listOf(diff.previousTitle, diff.currentTitle).filter { it.isNotBlank() }.joinToString(" → ")
        return buildString {
            append("${diff.scope}. ")
            if (pair.isNotBlank()) append("비교: $pair. ")
            if (changes.isNotEmpty()) append(changes.joinToString(" / ") + ". ")
            append(diff.note)
        }
    }

    private fun managementShift(diff: DisclosureDiff?): String {
        if (diff?.available != true) {
            return "DART 원문 본문 두 개가 확보되지 않아 경영진 표현의 강화·약화를 확정하지 않았습니다. 공시 제목 변화만으로 ‘경영진이 낙관적으로 바뀌었다’고 추정하지 않습니다."
        }
        val strategic = "경영진 전망·전략"
        return when {
            strategic in diff.newlyAppeared || strategic in diff.strengthened ->
                "전망·목표·전략 관련 표현이 이전 보고서보다 새로 등장하거나 강화된 신호가 있습니다. 실제 KPI 개선이 뒤따르는지 확인해야 하며 표현 변화만으로 실적 개선을 확정하지 않습니다."
            strategic in diff.disappeared || strategic in diff.weakened ->
                "전망·목표·전략 관련 표현이 이전보다 사라지거나 약해진 신호가 있습니다. 숫자가 아직 좋아도 경영진의 강조점 약화가 선행 신호인지 다음 분기에서 확인합니다."
            else ->
                "전망·목표·전략 관련 표현에서 뚜렷한 방향 변화가 잡히지 않았습니다. 변화가 없는 경우에도 이를 긍정·부정 신호로 과장하지 않습니다."
        }
    }

    private fun consequence(regime: Regime, valuation: Level, momentum: Level, watch: List<String>): String {
        val base = when (regime) {
            Regime.QUALITY_GROWTH ->
                "이 조합이 지속되면 핵심은 성장률 자체보다 높은 수익성이 유지되는 상태에서 매출 기반이 더 넓어지는 것입니다. 수요·가격·제품 믹스 중 실제 동인이 반복되고, 피어 대비 마진 우위가 유지되면 이익의 질이 확인됩니다. 반대로 외형은 유지되는데 마진 우위가 먼저 무너지면 성장의 질이 약해지는 초기 신호로 봐야 합니다."
            Regime.REVENUE_FIRST_GROWTH ->
                "가장 중요한 분기점은 ‘마진의 후행 회복’입니다. 선투자가 원인이었다면 매출 성장 이후 가동률 상승·고정비 흡수·제품 믹스 개선으로 마진이 따라와야 합니다. 매출만 계속 늘고 마진이 회복되지 않으면 투자 단계가 아니라 구조적으로 수익성이 낮은 성장일 가능성이 커집니다."
            Regime.EFFICIENCY_WITHOUT_GROWTH ->
                "마진 개선만 이어지고 매출이 회복되지 않으면 비용 절감의 한계에 도달할 수 있습니다. 반대로 개선된 비용 구조 위에 신규 고객·수주·물량이 다시 붙으면 작은 외형 회복이 큰 이익 증가로 연결되는 영업레버리지 구간이 시작될 수 있습니다."
            Regime.FUNDAMENTAL_COMPRESSION ->
                "회복은 보통 매출보다 선행 KPI에서 먼저 보입니다. 신규 수주, 가격 안정, 재고 정상화, 원가 감소, 가동률 회복 같은 신호가 나온 뒤 매출과 마진이 순차적으로 돌아서는지 확인해야 합니다. 그런 단서 없이 PER만 낮아지는 것은 회복이 아니라 주가 하락의 결과일 수 있습니다."
            Regime.FINANCIAL_PARTIAL ->
                "금융업은 ROE·NIM·손해율·충당금·자본비율 같은 전용 KPI가 개선되는지 확인하기 전까지 네 지표만으로 향후 이익 경로를 확정하기 어렵습니다. 전용 KPI가 좋아지고 그 다음 이익·주주환원이 따라오는 순서가 필요합니다."
            Regime.PARTIAL ->
                "혼합 신호에서는 다음 한두 분기 동안 어느 지표가 다른 지표를 끌고 가는지가 결론을 만듭니다. 성장 → 마진 → 가격의 순서로 확인되면 사업 개선이 시장에 전달되는 과정일 수 있고, 가격만 먼저 움직이면 기대 선반영 가능성이 큽니다."
        }

        val priceRisk = when {
            isNegative(valuation) && isPositive(momentum) ->
                " 현재 주가가 강하고 밸류에이션 부담도 높은 편이라면 좋은 실적 자체보다 ‘기대보다 더 좋은 실적’이 필요합니다. 기대에 못 미치는 순간 멀티플 압축과 주가 조정이 동시에 올 수 있습니다."
            isPositive(valuation) && isNegative(momentum) ->
                " 가격 부담은 낮아 보여도 주가 흐름이 약하므로 회복 증거가 나오기 전까지는 싸다는 이유만으로 반등을 전제하면 안 됩니다."
            else -> ""
        }
        val watchText = if (watch.isNotEmpty()) "\n\n다음 분기 반드시 볼 순서: ${watch.joinToString(" → ")}. 이 순서 중 앞단은 좋아지는데 뒷단이 안 따라오면 현재 원인 설명을 다시 검증합니다." else ""
        return base + priceRisk + watchText
    }

    private fun nextQuarterWatch(regime: Regime, kpi: IndustryKpiProfile): List<String> {
        val base = kpi.nextQuarterChain.take(4).toMutableList()
        when (regime) {
            Regime.REVENUE_FIRST_GROWTH -> if (base.none { it.contains("영업이익률") }) base += "영업이익률 회복"
            Regime.FUNDAMENTAL_COMPRESSION -> base.add(0, "수요·수주 반전")
            Regime.EFFICIENCY_WITHOUT_GROWTH -> base.add(0, "신규 고객·수주 재개")
            else -> Unit
        }
        return base.distinct().take(5)
    }

    private fun falsifiers(regime: Regime, profile: IndustryProfile, kpi: IndustryKpiProfile, stock: StockSummary): List<String> {
        val result = mutableListOf<String>()
        when (regime) {
            Regime.QUALITY_GROWTH -> {
                result += "다음 보고기간에 매출은 유지되지만 피어 대비 마진 우위가 사라지는지 확인"
                result += "성장이 일회성 계약·기술료·가격 급등 같은 비반복 요인에 의존했는지 확인"
            }
            Regime.REVENUE_FIRST_GROWTH -> {
                result += "선투자가 원인이라면 다음 몇 분기 안에 ${kpi.nextQuarterChain.take(2).joinToString("·")}와 마진 개선이 실제로 나타나는지 확인"
                result += "매출 증가가 할인·저가수주·저마진 제품 비중 확대 때문에 만들어졌는지 확인"
            }
            Regime.EFFICIENCY_WITHOUT_GROWTH -> {
                result += "마진 개선이 일회성 비용 감소나 자산매각 효과인지 확인"
                result += "신규 수주·고객·제품 없이 비용 절감만으로 이익을 유지하는지 확인"
            }
            Regime.FUNDAMENTAL_COMPRESSION -> {
                result += "${kpi.kpis.take(3).joinToString("·") { it.name }} 중 최소 하나가 실제로 돌아서는 선행 신호가 나오는지 확인"
                result += "낮은 밸류에이션이 실적 저점이 아니라 추가 이익 감소를 반영한 것인지 확인"
            }
            Regime.FINANCIAL_PARTIAL -> result += "ROE·NIM·손해율·충당금·자본비율을 추가했을 때 현재 결론이 유지되는지 확인"
            Regime.PARTIAL -> {
                result += "결측 지표가 채워졌을 때 현재 방향성이 뒤집히는지 확인"
                result += "전체시장 상대점수와 업종 피어 비교가 충돌한다면 업종 KPI가 어느 쪽을 지지하는지 확인"
            }
        }
        result += "${profile.keyDrivers.take(3).joinToString("·")} 중 실제로 실적을 움직인 변수가 무엇인지 최근 DART·IR에서 확인"
        if (stock.m03Per.peerComparison?.isSufficient == true) {
            result += "PER이 업종 중앙값과 벌어진 상태가 성장 지속성으로 설명되는지, 아니면 기대 과잉·업황 피크인지 확인"
        }
        return result.distinct().take(5)
    }

    private fun businessState(regime: Regime): String = when (regime) {
        Regime.QUALITY_GROWTH -> "좋음 — 성장과 수익성이 함께 작동"
        Regime.REVENUE_FIRST_GROWTH -> "확장 중이나 수익화 미검증"
        Regime.EFFICIENCY_WITHOUT_GROWTH -> "수익성 방어·성장 재개 확인 필요"
        Regime.FUNDAMENTAL_COMPRESSION -> "약화 — 외형과 수익성 동시 압박"
        Regime.FINANCIAL_PARTIAL -> "판단 제한 — 금융 전용 KPI 필요"
        Regime.PARTIAL -> "혼합 — 신호 충돌 해소 필요"
    }

    private fun priceBurden(valuation: Level, momentum: Level): String = when {
        valuation == Level.UNKNOWN -> "판단 보류 — 실적 PER 결측"
        isNegative(valuation) && isPositive(momentum) -> "높음 — 비싼 가격에 강한 기대까지 반영"
        isNegative(valuation) -> "높음 — 피어 대비 밸류에이션 부담"
        isPositive(valuation) && isNegative(momentum) -> "낮아 보이나 가치함정 확인 필요"
        isPositive(valuation) -> "상대적으로 낮음"
        else -> "중립"
    }

    private fun causeConfidence(
        stock: StockSummary,
        evidence: EvidenceBundle,
        highlights: List<ContextEvidence>,
        diff: DisclosureDiff?
    ): String {
        val metrics = listOf(stock.m01RevGrowth, stock.m02OpMargin, stock.m03Per, stock.m04Price6m)
        val peerCount = metrics.count { it.peerComparison?.isSufficient == true }
        return when {
            metrics.count { it.isAvailable } == 4 && peerCount >= 3 && diff?.available == true && evidence.primarySourceCount >= 2 -> "높음 — 4지표·피어·DART 원문 Diff가 함께 있음"
            metrics.count { it.isAvailable } >= 3 && peerCount >= 2 && evidence.primarySourceCount >= 1 && highlights.isNotEmpty() -> "중간 — 1차 자료는 있으나 원문 Diff 또는 일부 피어가 부족"
            else -> "낮음 — 원인을 확정할 근거가 부족"
        }
    }

    private fun futureUncertainty(
        stock: StockSummary,
        regime: Regime,
        valuation: Level,
        momentum: Level,
        evidence: EvidenceBundle
    ): String {
        var score = 0
        if (!stock.isCompositeComplete) score += 2
        if (regime == Regime.PARTIAL || regime == Regime.FINANCIAL_PARTIAL) score += 2
        if (regime == Regime.REVENUE_FIRST_GROWTH || regime == Regime.FUNDAMENTAL_COMPRESSION) score += 1
        if (isNegative(valuation) && isPositive(momentum)) score += 1
        if (evidence.disclosureDiff?.available != true) score += 1
        return when {
            score >= 5 -> "매우 높음 — 핵심 전제가 여러 개이고 반증 확인이 필수"
            score >= 3 -> "높음 — 다음 1~2개 보고기간의 KPI 확인 필요"
            score >= 1 -> "중간 — 현재 설명은 가능하지만 지속성 확인 필요"
            else -> "낮은 편 — 데이터가 비교적 정렬됐으나 예측 불확실성은 항상 존재"
        }
    }

    private fun confidenceNote(
        stock: StockSummary,
        evidence: EvidenceBundle,
        highlights: List<ContextEvidence>,
        diff: DisclosureDiff?,
        businessState: String,
        priceBurden: String,
        causeConfidence: String,
        uncertainty: String
    ): String {
        val metrics = listOf(stock.m01RevGrowth, stock.m02OpMargin, stock.m03Per, stock.m04Price6m)
        val available = metrics.count { it.isAvailable }
        val peerCount = metrics.count { it.peerComparison?.isSufficient == true }
        val source = when {
            !evidence.loaded -> "공시·뉴스 확인 전"
            evidence.error != null && evidence.all.isEmpty() -> "근거 연결 실패"
            else -> "1차 자료 ${evidence.primarySourceCount}건, 해석 사용 단서 ${highlights.size}건"
        }
        val diffState = if (diff?.available == true) "DART 원문 Diff 확인" else "DART 원문 Diff 미확인/보조 비교만 사용"
        return "사업 상태: $businessState. 가격 부담: $priceBurden. 원인 분석 확신도: $causeConfidence. 미래 예측 불확실성: $uncertainty. 정량지표 $available/4, 유효 업종 피어 $peerCount/4, $source, $diffState. 이 네 항목은 하나의 점수로 합치지 않습니다."
    }

    private fun tagEvidence(items: List<ContextEvidence>): List<TaggedEvidence> = items.map { evidence ->
        val t = (evidence.title + " " + evidence.bodyText.take(20_000)).replace(" ", "").lowercase()
        fun has(vararg words: String) = words.any { t.contains(it.lowercase().replace(" ", "")) }
        val signals = buildSet {
            if (has("수주", "공급계약", "납품", "판매계약", "고객사", "수주잔고", "수요증가")) add(EvidenceSignal.ORDER_DEMAND)
            if (has("투자", "증설", "공장", "설비", "capex", "연구개발", "r&d", "임상", "신약개발")) add(EvidenceSignal.INVESTMENT_RND)
            if (has("원가", "비용", "인건비", "손실", "적자", "충당금", "감가상각", "수율")) add(EvidenceSignal.MARGIN_COST)
            if (has("가격인상", "판가", "단가", "가격인하", "할인", "제품믹스", "고부가")) add(EvidenceSignal.PRICING_MIX)
            if (has("잠정실적", "영업이익", "매출액", "실적발표", "실적공시")) add(EvidenceSignal.EARNINGS)
            if (has("유상증자", "전환사채", "cb발행", "신주인수권", "bw발행", "제3자배정", "자금조달")) add(EvidenceSignal.FINANCING_DILUTION)
            if (has("배당", "자사주", "주식소각", "소각결정", "주주환원")) add(EvidenceSignal.SHAREHOLDER_RETURN)
            if (has("합병", "인수", "매각", "분할", "구조조정", "사업재편")) add(EvidenceSignal.MNA_RESTRUCTURE)
            if (has("규제", "소송", "과징금", "제재", "압수수색", "회계처리", "감사의견")) add(EvidenceSignal.REGULATION_LEGAL)
            if (has("허가", "승인", "품목허가", "신제품", "출시", "임상결과", "특허")) add(EvidenceSignal.PRODUCT_APPROVAL)
            if (has("계약해지", "계약취소", "수주취소", "파기", "철회")) add(EvidenceSignal.CONTRACT_RISK)
            if (has("전망", "목표", "계획", "전략", "가이던스", "집중")) add(EvidenceSignal.MANAGEMENT_OUTLOOK)
        }
        TaggedEvidence(evidence, signals)
    }

    private fun selectEvidence(regime: Regime, items: List<TaggedEvidence>): List<TaggedEvidence> {
        val priority = when (regime) {
            Regime.QUALITY_GROWTH -> listOf(EvidenceSignal.ORDER_DEMAND, EvidenceSignal.PRICING_MIX, EvidenceSignal.PRODUCT_APPROVAL, EvidenceSignal.INVESTMENT_RND)
            Regime.REVENUE_FIRST_GROWTH -> listOf(EvidenceSignal.INVESTMENT_RND, EvidenceSignal.MARGIN_COST, EvidenceSignal.PRICING_MIX, EvidenceSignal.ORDER_DEMAND)
            Regime.EFFICIENCY_WITHOUT_GROWTH -> listOf(EvidenceSignal.PRICING_MIX, EvidenceSignal.MNA_RESTRUCTURE, EvidenceSignal.ORDER_DEMAND, EvidenceSignal.SHAREHOLDER_RETURN)
            Regime.FUNDAMENTAL_COMPRESSION -> listOf(EvidenceSignal.CONTRACT_RISK, EvidenceSignal.MARGIN_COST, EvidenceSignal.FINANCING_DILUTION, EvidenceSignal.REGULATION_LEGAL)
            else -> listOf(EvidenceSignal.EARNINGS, EvidenceSignal.ORDER_DEMAND, EvidenceSignal.INVESTMENT_RND)
        }

        fun signalRank(tagged: TaggedEvidence): Int = priority.indexOfFirst { it in tagged.signals }.let { if (it < 0) Int.MAX_VALUE else it }
        return items.sortedWith(
            compareBy<TaggedEvidence> { it.evidence.sourceTier.priority }
                .thenBy { signalRank(it) }
                .thenByDescending { it.evidence.publishedAt }
        ).let { ranked ->
            val signaled = ranked.filter { it.signals.isNotEmpty() }
            if (signaled.isNotEmpty()) signaled else ranked.take(3)
        }
    }

    private fun sourceLabel(item: ContextEvidence): String = when (item.sourceTier) {
        EvidenceSourceTier.DART_PRIMARY -> "DART"
        EvidenceSourceTier.COMPANY_IR -> "IR"
        EvidenceSourceTier.COMPANY_OFFICIAL -> "회사공식"
        EvidenceSourceTier.TRUSTED_MEDIA -> if (item.kind == EvidenceKind.NEWS) "뉴스" else item.source
        EvidenceSourceTier.OTHER -> item.source.ifBlank { "기타" }
    }
}

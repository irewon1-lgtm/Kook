package com.krstock.v3.data.analysis

import com.krstock.v3.data.model.ContextEvidence
import com.krstock.v3.data.model.EvidenceBundle
import com.krstock.v3.data.model.IntegratedAnalysis
import com.krstock.v3.data.model.MetricValue
import com.krstock.v3.data.model.StockSummary

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
        CONTRACT_RISK
    }

    private data class TaggedEvidence(
        val evidence: ContextEvidence,
        val signals: Set<EvidenceSignal>
    )

    fun analyze(stock: StockSummary, evidence: EvidenceBundle = EvidenceBundle(stock.issuerId)): IntegratedAnalysis {
        val profile = IndustryInsightCatalog.profileFor(stock.sector)
        val growth = level(stock.m01RevGrowth)
        val margin = level(stock.m02OpMargin)
        val valuation = level(stock.m03Per)
        val momentum = level(stock.m04Price6m)
        val regime = regime(stock, growth, margin)
        val tagged = tagEvidence(evidence.all)
        val highlights = selectEvidence(regime, tagged).map { it.evidence }.take(5)

        return IntegratedAnalysis(
            regimeTitle = regimeTitle(regime, valuation, momentum),
            thesis = thesis(regime, valuation, momentum),
            industryContext = industryContext(profile, stock),
            combinationMeaning = combinationMeaning(regime, valuation, momentum, stock),
            causeInvestigation = causeInvestigation(regime, profile, tagged, evidence),
            consequence = consequence(regime, valuation, momentum),
            falsifiers = falsifiers(regime, profile, stock),
            confidenceNote = confidence(stock, evidence, highlights),
            evidenceHighlights = highlights
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
                "사업 규모가 커지는 동시에 그 성장이 이익으로도 남는 패턴입니다. 이 조합의 핵심은 ‘많이 파는 것’과 ‘남기는 것’이 같이 좋아진다는 점이라, 네 지표 중 사업의 질을 가장 긍정적으로 읽을 수 있는 형태입니다."
            Regime.REVENUE_FIRST_GROWTH ->
                "매출은 앞서가는데 수익성이 따라오지 않는 패턴입니다. 이 경우 진짜 질문은 ‘성장이 좋은가’가 아니라 ‘왜 성장의 대가로 마진을 포기하고 있는가’입니다. 증설·R&D·마케팅 같은 선투자라면 미래 마진 개선으로 이어질 수 있지만, 가격경쟁이나 저마진 제품 비중 확대라면 성장의 질이 낮을 수 있습니다."
            Regime.EFFICIENCY_WITHOUT_GROWTH ->
                "외형은 약하지만 남기는 힘은 버티는 패턴입니다. 비용 절감, 제품 믹스 개선, 가격 인상처럼 운영 효율이 좋아진 것일 수 있고, 반대로 성장이 멈춘 성숙기업이 이익만 방어하는 상황일 수도 있습니다. 다음 단계는 다시 성장할 동력이 있는지 확인하는 것입니다."
            Regime.FUNDAMENTAL_COMPRESSION ->
                "외형과 수익성이 같이 약해지는 패턴입니다. 수요 둔화와 비용 부담이 동시에 나타날 때 흔하며, 단순히 PER이 낮다는 이유만으로 접근하면 ‘싸 보이는 이유가 있는 주식’이 될 가능성이 큽니다. 회복의 선행 신호가 나오기 전까지는 숫자의 악화를 설명할 근거가 더 중요합니다."
            Regime.FINANCIAL_PARTIAL ->
                "현재 네 지표만으로는 금융업의 수익구조를 제대로 설명할 수 없습니다. 금융사는 일반 영업이익률 대신 ROE·NIM·손해율·충당금·자본비율을 봐야 하므로, 여기서는 성장·PER·가격 흐름만 보조적으로 해석하고 결론의 강도를 낮춰야 합니다."
            Regime.PARTIAL ->
                "네 지표가 한 방향으로 정렬되지 않거나 일부가 비어 있습니다. 이런 경우 한 숫자를 억지로 대표값으로 삼지 않고, 서로 충돌하는 신호가 무엇인지와 어떤 추가 정보가 그 충돌을 풀어줄지 보는 것이 맞습니다."
        }

        val overlay = when {
            isNegative(valuation) && isPositive(momentum) ->
                " 여기에 높은 밸류에이션과 강한 주가 흐름이 겹치면 시장이 현재 실적보다 미래 개선을 먼저 사고 있는 상태입니다. 좋은 회사인지보다 ‘이미 좋은 미래를 얼마까지 가격에 넣었는가’가 리스크의 중심이 됩니다."
            isPositive(valuation) && isNegative(momentum) ->
                " 반대로 상대적으로 부담이 낮은 가격에도 주가 흐름이 약하다면 시장이 아직 숫자의 지속성을 믿지 않거나, 네 지표 밖의 위험을 가격에 반영하고 있을 가능성을 확인해야 합니다."
            isPositive(valuation) && isPositive(momentum) ->
                " 가격 부담이 상대적으로 낮고 흐름까지 받쳐주면 재평가가 진행되는 조합일 수 있습니다. 다만 경기민감 업종에서는 이익 정점 때문에 싸 보이는 착시가 아닌지 먼저 확인해야 합니다."
            isNegative(valuation) && isNegative(momentum) ->
                " 가격 부담이 높은데 흐름까지 약하면 기대가 내려오는 초기 구간일 수 있습니다. 이 조합에서는 ‘좋아질 것’이라는 가정보다 실제 개선 증거가 먼저 필요합니다."
            else -> ""
        }
        return core + overlay
    }

    private fun industryContext(profile: IndustryProfile, stock: StockSummary): String = buildString {
        append("${profile.label}에서 먼저 봐야 할 것은 단순한 성장률의 높고 낮음이 아닙니다. ")
        append(profile.economics)
        append("\n\n성장 숫자를 읽을 때는 ")
        append(profile.growthLens)
        append(" 수익성은 ")
        append(profile.marginLens)
        append(" 밸류에이션은 ")
        append(profile.valuationLens)
        if (stock.sector != profile.label) {
            append("\n\n현재 KRX 세부업종은 ‘${stock.sector}’이고, 위 설명은 이를 더 넓은 경제구조로 묶어 읽은 것입니다. 세부업종 피어 중앙값이 충분하면 실제 비교는 넓은 산업 설명보다 그 세부 피어를 우선합니다.")
        }
    }

    private fun combinationMeaning(
        regime: Regime,
        valuation: Level,
        momentum: Level,
        stock: StockSummary
    ): String {
        val business = when (regime) {
            Regime.QUALITY_GROWTH ->
                "성장과 마진이 같이 강하다는 것은 매출 증가가 할인·저가수주 같은 희생만으로 만들어진 가능성이 낮다는 뜻입니다. 특히 피어보다 마진까지 우위라면 규모 확대가 비용 구조를 망가뜨리지 않고 있다는 신호가 됩니다. 여기서 다음 질문은 ‘이 우위가 반복 가능한가’입니다."
            Regime.REVENUE_FIRST_GROWTH ->
                "매출 증가와 마진 약화가 동시에 보이면 사업은 확장 중이지만 경제성은 아직 증명되지 않은 상태입니다. 이 조합은 크게 세 갈래로 나뉩니다. 첫째, 증설·R&D·영업망 확대처럼 지금 비용을 쓰고 나중에 회수하는 선투자. 둘째, 원가 상승이나 신규 라인 초기 수율 때문에 일시적으로 마진이 눌린 경우. 셋째, 가격 인하·저마진 수주로 외형만 키우는 경우입니다. 세 경우는 겉으로 같은 숫자를 만들지만 투자 의미는 완전히 다릅니다."
            Regime.EFFICIENCY_WITHOUT_GROWTH ->
                "매출이 약한데 마진이 버티거나 좋아지면 비용구조 개선이나 고마진 제품 집중이 진행될 수 있습니다. 이 패턴은 현금창출은 좋아질 수 있지만 성장 재개가 없으면 밸류에이션 확장이 제한될 수 있습니다. 따라서 이익률 개선을 ‘좋은 실적’으로 끝내지 말고, 그 개선이 매출 회복을 위한 체력을 만드는지 봐야 합니다."
            Regime.FUNDAMENTAL_COMPRESSION ->
                "매출과 마진의 동반 약화는 단순한 비용 문제보다 수요와 가격결정력까지 흔들릴 가능성을 시사합니다. 매출이 줄면서 마진도 떨어지면 고정비 부담이 커지는 역영업레버리지가 나타날 수 있어, 회복 시점까지 이익 감소가 매출 감소보다 더 클 수도 있습니다."
            Regime.FINANCIAL_PARTIAL ->
                "금융업에서는 일반 영업이익률이 빠져 있기 때문에 네 지표의 조합 자체가 불완전합니다. 낮은 PER이 자산건전성 우려를 반영하는지, 주가 모멘텀이 금리·배당 기대에서 나온 것인지 구분하려면 금융 전용 수익성 지표가 필요합니다."
            Regime.PARTIAL ->
                "혼합 신호는 오히려 중요한 정보입니다. 성장·수익성·가격·밸류에이션이 서로 다른 이야기를 할 때는 시장과 실물 사업의 시계가 어긋난 경우가 많습니다. 현재 숫자를 한 문장으로 단정하기보다 어떤 지표가 선행하고 어떤 지표가 후행하는지 구분해야 합니다."
        }

        val market = when {
            isNegative(valuation) && isPositive(momentum) ->
                "PER 부담이 높은데 주가도 강하면 시장이 ‘앞으로 더 좋아질 것’이라는 가정을 적극적으로 가격에 넣고 있는 상태입니다. 이때는 현재 숫자가 좋아지는 것만으로 부족하고, 좋아지는 속도가 시장 기대보다 빨라야 주가가 계속 버틸 수 있습니다."
            isPositive(valuation) && isPositive(momentum) ->
                "상대적으로 부담이 낮은 가격과 강한 흐름이 같이 있으면 실적 개선을 시장이 뒤늦게 인정하는 재평가 구간일 수 있습니다. 다만 사이클 업종에서는 현재 이익이 비정상적으로 높은 정점이라 PER이 낮아진 것인지 반드시 구분해야 합니다."
            isNegative(valuation) && isNegative(momentum) ->
                "밸류에이션이 높은데 흐름이 약하면 기대의 재조정이 진행되는 조합입니다. 좋은 스토리보다 실적이 기대를 따라잡는지가 중요해집니다."
            isPositive(valuation) && isNegative(momentum) ->
                "싸 보이는데도 주가가 약하면 시장이 네 지표 밖의 문제를 의심하고 있을 수 있습니다. 일회성 이익, 업황 피크, 자금조달, 대형주주 매도, 규제·소송 같은 외부 변수를 공시에서 확인해야 ‘저평가’와 ‘가치 함정’을 구분할 수 있습니다."
            else ->
                "밸류에이션과 모멘텀이 극단적으로 한쪽으로 정렬되지 않았으므로 사업 지표의 지속성이 가격 판단보다 우선입니다."
        }

        val peerNote = if (listOf(stock.m01RevGrowth, stock.m02OpMargin, stock.m03Per, stock.m04Price6m)
                .count { it.peerComparison?.isSufficient == true } >= 3
        ) {
            "이 해석은 절대 숫자만 본 것이 아니라 대부분의 지표를 같은 업종 중앙값과 대조한 뒤 만든 것입니다. 즉 ‘10%가 높다’처럼 업종을 무시한 절대 기준보다 해당 산업에서 실제로 우위인지 열위인지에 더 무게를 둡니다."
        } else {
            "일부 지표는 피어 표본이 부족하므로 전체시장 상대위치와 절대 방향을 보조적으로 사용했습니다. 피어가 부족한 지표는 산업 평균을 억지로 만들어 넣지 않았습니다."
        }
        return "$business\n\n$market\n\n$peerNote"
    }

    private fun causeInvestigation(
        regime: Regime,
        profile: IndustryProfile,
        tagged: List<TaggedEvidence>,
        evidence: EvidenceBundle
    ): String {
        if (!evidence.loaded) {
            return "최근 뉴스·공시 확인이 아직 끝나지 않았습니다. 이 상태에서는 ‘투자를 많이 해서 마진이 낮다’ 같은 원인을 사실처럼 단정하지 않습니다. 숫자 조합이 허용하는 가설만 제시하고, 실제 원인은 최근 공시와 뉴스가 도착한 뒤 교차확인합니다."
        }
        if (evidence.error != null && tagged.isEmpty()) {
            return "최근 뉴스·공시 원문 목록을 불러오지 못해 원인 검증을 보류했습니다. 네 지표만으로는 증설, R&D, 가격경쟁, 일회성 비용 중 어느 것이 원인인지 확정할 수 없으므로 추정 문장을 사실처럼 쓰지 않습니다."
        }
        if (tagged.isEmpty()) {
            return "최근 확인 가능한 뉴스·공시 제목에서 현재 숫자 조합을 직접 설명할 만한 단서가 잡히지 않았습니다. 이런 경우에는 원인을 지어내는 대신 ${profile.keyDrivers.joinToString("·")} 항목을 다음 공시에서 우선 확인하는 편이 맞습니다."
        }

        val bySignal = EvidenceSignal.entries.associateWith { signal -> tagged.filter { signal in it.signals } }
        val findings = mutableListOf<String>()

        fun mention(signal: EvidenceSignal, sentence: String) {
            val items = bySignal[signal].orEmpty()
            if (items.isNotEmpty()) {
                val titles = items.take(2).joinToString(" / ") { "‘${it.evidence.title.take(52)}’" }
                findings += "$sentence 최근 근거 제목으로 $titles 가 확인됩니다."
            }
        }

        when (regime) {
            Regime.REVENUE_FIRST_GROWTH -> {
                mention(EvidenceSignal.INVESTMENT_RND,
                    "증설·투자·R&D 단서가 있다면 현재의 낮은 마진은 미래 성장을 위해 비용이 먼저 발생하는 선투자형 패턴일 가능성이 커집니다.")
                mention(EvidenceSignal.ORDER_DEMAND,
                    "수주·공급계약 단서가 같이 보이면 매출 성장이 실제 수요나 주문 증가에서 왔을 가능성을 보강합니다.")
                mention(EvidenceSignal.MARGIN_COST,
                    "원가·비용·손실 단서가 있다면 외형 성장과 별개로 비용 부담이 마진을 누르는 시나리오를 우선 점검해야 합니다.")
                mention(EvidenceSignal.PRICING_MIX,
                    "가격·제품믹스 단서는 매출이 늘면서도 마진이 약한 이유가 저가 제품 비중이나 판가 정책에 있는지 확인하는 열쇠가 됩니다.")
            }
            Regime.QUALITY_GROWTH -> {
                mention(EvidenceSignal.ORDER_DEMAND,
                    "수주·공급계약 단서가 반복되면 현재의 성장과 수익성이 실제 수요 확대로 뒷받침되고 있을 가능성이 높아집니다.")
                mention(EvidenceSignal.PRODUCT_APPROVAL,
                    "신제품·허가·임상·출시 단서는 기존 사업만이 아니라 신규 성장축이 실적에 더해질 수 있음을 보여줍니다.")
                mention(EvidenceSignal.INVESTMENT_RND,
                    "투자 단서가 있는데도 마진이 유지된다면 현재 사업의 현금창출력이 선투자 비용을 흡수하고 있는지 볼 필요가 있습니다.")
            }
            Regime.EFFICIENCY_WITHOUT_GROWTH -> {
                mention(EvidenceSignal.MNA_RESTRUCTURE,
                    "구조조정·매각·합병 단서는 외형보다 수익성을 우선하는 사업 재편이 진행 중일 가능성을 보여줍니다.")
                mention(EvidenceSignal.PRICING_MIX,
                    "가격 인상이나 제품믹스 단서는 매출이 크게 늘지 않아도 마진을 방어할 수 있는 설명과 맞닿아 있습니다.")
                mention(EvidenceSignal.SHAREHOLDER_RETURN,
                    "성장보다 현금창출이 중요한 구간에서는 배당·자사주 같은 주주환원 공시가 밸류에이션의 핵심 촉매가 될 수 있습니다.")
            }
            Regime.FUNDAMENTAL_COMPRESSION -> {
                mention(EvidenceSignal.MARGIN_COST,
                    "원가·비용·손실 단서가 보이면 수요 둔화에 비용 압박까지 겹치는 이중 압박 가능성이 커집니다.")
                mention(EvidenceSignal.CONTRACT_RISK,
                    "계약 취소·해지 단서는 매출 둔화가 단순 경기 요인이 아니라 주문 기반 자체의 훼손일 수 있음을 뜻합니다.")
                mention(EvidenceSignal.FINANCING_DILUTION,
                    "자금조달 단서가 함께 있다면 실적 약화가 재무 부담이나 희석 위험으로 이어지는지 추가 확인해야 합니다.")
            }
            else -> {
                mention(EvidenceSignal.EARNINGS,
                    "실적 관련 공시는 현재 숫자의 변화를 설명하는 가장 직접적인 출발점입니다.")
                mention(EvidenceSignal.ORDER_DEMAND,
                    "수주·계약 단서는 향후 매출 방향을 확인하는 선행 자료가 됩니다.")
                mention(EvidenceSignal.INVESTMENT_RND,
                    "투자·R&D 단서는 현재 비용과 향후 성장의 연결고리를 확인하는 자료가 됩니다.")
            }
        }

        mention(EvidenceSignal.FINANCING_DILUTION,
            "유상증자·전환사채 같은 자금조달은 사업 성장과 별개로 주당가치와 수급에 부담을 줄 수 있어 네 지표 밖의 위험으로 따로 봐야 합니다.")
        mention(EvidenceSignal.REGULATION_LEGAL,
            "규제·소송·제재 단서는 정량지표가 뒤늦게 반영할 수 있는 비재무 리스크이므로 별도 할인요인으로 봐야 합니다.")

        if (findings.isEmpty()) {
            return "최근 뉴스·공시를 확인했지만 제목만으로 현재 숫자의 원인을 직접 연결할 수 있는 단서는 부족했습니다. 확인되지 않은 인과관계를 만들지 않고, ${profile.keyDrivers.joinToString("·")} 항목을 원문 공시에서 우선 확인해야 합니다."
        }

        return findings.distinct().take(5).joinToString("\n\n") +
            "\n\n중요: 위 내용은 뉴스·공시 제목과 정량 패턴이 서로 맞물리는지를 본 ‘원인 후보 검증’입니다. 제목만으로 비용 항목이나 매출 인식 원인을 확정하지 않고, 실제 사업보고서·분기보고서 원문에서 최종 확인해야 합니다."
    }

    private fun consequence(regime: Regime, valuation: Level, momentum: Level): String {
        val base = when (regime) {
            Regime.QUALITY_GROWTH ->
                "이 패턴이 다음 분기에도 유지되면 시장은 단순한 일회성 실적보다 구조적 성장으로 인식할 가능성이 커지고, 밸류에이션 재평가가 이어질 여지가 생깁니다. 반대로 매출은 유지되는데 마진이 먼저 꺾이면 성장의 질이 약해지는 초기 신호로 봐야 합니다."
            Regime.REVENUE_FIRST_GROWTH ->
                "가장 중요한 분기점은 ‘마진의 후행 회복’입니다. 선투자가 원인이었다면 매출 성장 이후 고정비 흡수와 제품 믹스 개선으로 마진이 따라와야 합니다. 매출만 계속 늘고 마진이 회복되지 않으면 투자 단계가 아니라 구조적으로 수익성이 낮은 성장일 가능성이 커집니다."
            Regime.EFFICIENCY_WITHOUT_GROWTH ->
                "마진 개선만 이어지고 매출이 회복되지 않으면 비용 절감의 한계에 도달할 수 있습니다. 반대로 개선된 비용 구조 위에 매출이 다시 붙으면 작은 외형 회복이 큰 이익 증가로 연결되는 영업레버리지 구간이 시작될 수 있습니다."
            Regime.FUNDAMENTAL_COMPRESSION ->
                "회복은 보통 매출보다 선행 단서에서 먼저 보입니다. 신규 수주, 가격 안정, 재고 정상화, 비용 감소 같은 신호가 나온 뒤 매출과 마진이 순차적으로 돌아서는지 확인해야 합니다. 그런 단서 없이 PER만 낮아지는 것은 회복이 아니라 주가 하락의 결과일 수 있습니다."
            Regime.FINANCIAL_PARTIAL ->
                "금융업은 ROE·NIM·손해율·충당금 등 전용 지표가 개선되는지 확인하기 전까지 네 지표만으로 향후 이익 경로를 확정하기 어렵습니다."
            Regime.PARTIAL ->
                "혼합 신호에서는 다음 한두 분기 동안 어느 지표가 다른 지표를 끌고 가는지가 결론을 만듭니다. 성장→마진→가격의 순서로 확인되면 사업 개선이 시장에 전달되는 과정일 수 있고, 가격만 먼저 움직이면 기대 선반영 가능성이 큽니다."
        }

        val priceRisk = when {
            isNegative(valuation) && isPositive(momentum) ->
                " 현재 주가가 강하고 밸류에이션 부담도 높은 편이라면 좋은 실적 자체보다 ‘기대보다 더 좋은 실적’이 필요합니다. 기대에 못 미치는 순간 멀티플 압축과 주가 조정이 동시에 올 수 있습니다."
            isPositive(valuation) && isNegative(momentum) ->
                " 가격 부담은 낮아 보여도 주가 흐름이 약하므로, 회복 증거가 나오기 전까지는 싸다는 이유만으로 반등을 전제하면 안 됩니다."
            else -> ""
        }
        return base + priceRisk
    }

    private fun falsifiers(regime: Regime, profile: IndustryProfile, stock: StockSummary): List<String> {
        val result = mutableListOf<String>()
        when (regime) {
            Regime.QUALITY_GROWTH -> {
                result += "다음 보고기간에 매출은 유지되지만 피어 대비 마진 우위가 사라지는지 확인"
                result += "성장이 일회성 계약·기술료·가격 급등 같은 비반복 요인에 의존했는지 확인"
            }
            Regime.REVENUE_FIRST_GROWTH -> {
                result += "투자·증설·R&D가 원인이라면 다음 몇 분기 안에 가동률 또는 마진 개선이 실제로 나타나는지 확인"
                result += "매출 증가가 할인·저가수주·저마진 제품 비중 확대 때문에 만들어졌는지 확인"
            }
            Regime.EFFICIENCY_WITHOUT_GROWTH -> {
                result += "마진 개선이 일회성 비용 감소나 자산매각 효과인지 확인"
                result += "신규 수주·고객·제품 없이 비용 절감만으로 이익을 유지하는지 확인"
            }
            Regime.FUNDAMENTAL_COMPRESSION -> {
                result += "신규 수주·가격·재고·원가 중 최소 하나가 실제로 돌아서는 선행 신호가 나오는지 확인"
                result += "낮은 밸류에이션이 실적 저점이 아니라 추가 이익 감소를 반영한 것인지 확인"
            }
            Regime.FINANCIAL_PARTIAL -> {
                result += "ROE·NIM·손해율·충당금·자본비율을 추가해 현재 결론이 유지되는지 확인"
            }
            Regime.PARTIAL -> {
                result += "결측 지표가 채워졌을 때 현재 방향성이 뒤집히는지 확인"
                result += "전체시장 상대점수와 업종 피어 비교가 서로 충돌하는 이유를 확인"
            }
        }
        result += "${profile.keyDrivers.take(3).joinToString("·")} 중 실제로 실적을 움직인 변수가 무엇인지 최근 공시에서 확인"
        if (stock.m03Per.peerComparison?.isSufficient == true) {
            result += "PER이 업종 중앙값과 벌어진 상태가 성장 지속성으로 설명되는지, 아니면 기대 과잉/업황 피크인지 확인"
        }
        return result.distinct().take(5)
    }

    private fun confidence(
        stock: StockSummary,
        evidence: EvidenceBundle,
        highlights: List<ContextEvidence>
    ): String {
        val metrics = listOf(stock.m01RevGrowth, stock.m02OpMargin, stock.m03Per, stock.m04Price6m)
        val available = metrics.count { it.isAvailable }
        val peerCount = metrics.count { it.peerComparison?.isSufficient == true }
        val grade = when {
            available == 4 && peerCount >= 3 && evidence.loaded && highlights.size >= 2 -> "높음"
            available >= 3 && peerCount >= 2 -> "중간"
            else -> "낮음"
        }
        val evidenceText = when {
            !evidence.loaded -> "뉴스·공시는 아직 확인 전"
            evidence.error != null && evidence.all.isEmpty() -> "뉴스·공시 연결 실패"
            highlights.isEmpty() -> "최근 근거는 확인했지만 직접 연결되는 단서가 약함"
            else -> "최근 뉴스·공시 단서 ${highlights.size}건을 정량 패턴과 교차확인"
        }
        return "해석 확신도: $grade. 정량지표 $available/4, 유효한 업종 피어 비교 $peerCount/4, $evidenceText. 확신도는 투자 성공확률이 아니라 ‘현재 설명에 필요한 데이터가 얼마나 채워졌는지’를 뜻합니다."
    }

    private fun tagEvidence(items: List<ContextEvidence>): List<TaggedEvidence> = items.map { evidence ->
        val t = evidence.title.replace(" ", "").lowercase()
        fun has(vararg words: String) = words.any { t.contains(it.lowercase().replace(" ", "")) }
        val signals = buildSet {
            if (has("수주", "공급계약", "납품", "판매계약", "고객사", "수주잔고")) add(EvidenceSignal.ORDER_DEMAND)
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
        }
        TaggedEvidence(evidence, signals)
    }

    private fun selectEvidence(regime: Regime, items: List<TaggedEvidence>): List<TaggedEvidence> {
        val priority = when (regime) {
            Regime.QUALITY_GROWTH -> listOf(EvidenceSignal.ORDER_DEMAND, EvidenceSignal.PRODUCT_APPROVAL, EvidenceSignal.INVESTMENT_RND)
            Regime.REVENUE_FIRST_GROWTH -> listOf(EvidenceSignal.INVESTMENT_RND, EvidenceSignal.MARGIN_COST, EvidenceSignal.PRICING_MIX, EvidenceSignal.ORDER_DEMAND)
            Regime.EFFICIENCY_WITHOUT_GROWTH -> listOf(EvidenceSignal.PRICING_MIX, EvidenceSignal.MNA_RESTRUCTURE, EvidenceSignal.SHAREHOLDER_RETURN)
            Regime.FUNDAMENTAL_COMPRESSION -> listOf(EvidenceSignal.CONTRACT_RISK, EvidenceSignal.MARGIN_COST, EvidenceSignal.FINANCING_DILUTION, EvidenceSignal.REGULATION_LEGAL)
            else -> listOf(EvidenceSignal.EARNINGS, EvidenceSignal.ORDER_DEMAND, EvidenceSignal.INVESTMENT_RND)
        }
        val ranked = items.sortedBy { tagged ->
            priority.indexOfFirst { it in tagged.signals }.let { if (it < 0) Int.MAX_VALUE else it }
        }
        val signaled = ranked.filter { it.signals.isNotEmpty() }
        return if (signaled.isNotEmpty()) signaled else items.take(3)
    }
}

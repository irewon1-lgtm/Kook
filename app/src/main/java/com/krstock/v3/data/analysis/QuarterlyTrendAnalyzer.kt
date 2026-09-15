package com.krstock.v3.data.analysis

import com.krstock.v3.data.model.IntegratedAnalysis
import com.krstock.v3.data.model.QuarterlyHistory
import com.krstock.v3.data.model.QuarterlyPoint
import kotlin.math.abs

object QuarterlyTrendAnalyzer {
    fun enrich(analysis: IntegratedAnalysis, history: QuarterlyHistory): IntegratedAnalysis {
        val available = history.availableQuarterCount
        return analysis.copy(
            quarterlyTrend = summarize(history),
            quarterlySignal = compactSignal(history),
            quarterlyCoverage = when {
                !history.loaded -> "불러오는 중"
                available >= 8 -> "8/8 연속분기 확인"
                available >= 4 -> "$available/8 연속분기 확인"
                else -> "$available/8 연속분기 · 판독 보류"
            }
        )
    }

    fun summarize(history: QuarterlyHistory): String {
        if (!history.loaded) return "4~8분기 시계열을 불러오는 중입니다."
        val usable = history.comparablePoints
        if (usable.size < 4) {
            val reason = when {
                history.hasTrailingGap -> "최신 대상 분기의 실제 매출이 비어 있습니다."
                history.points.any { it.revenue != null } && usable.isEmpty() -> "최신 분기와 같은 회계범위의 연속 자료를 만들 수 없습니다."
                else -> "비교 가능한 연속 실제 분기가 ${usable.size}개뿐입니다."
            }
            return "$reason 결측 분기를 건너뛰거나 CFS/OFS 범위를 섞어 추세를 만들지 않으므로 판독을 보류합니다."
        }

        val latest = usable.last()
        val recent4 = usable.takeLast(4)
        val revenueTrend = direction(recent4.mapNotNull { it.revenue })
        val marginValues = recent4.mapNotNull { it.operatingMargin }
        val marginTrend = if (marginValues.size >= 3) direction(marginValues) else "확인 부족"
        val latestYoY = reliableYoY(usable, usable.lastIndex)
        val previousYoY = reliableYoY(usable, usable.lastIndex - 1)
        val latestQoQ = reliableQoQ(usable)
        val latestMargin = latest.operatingMargin

        return buildString {
            append("동일 회계범위(${history.comparisonScope.ifBlank { "확인가능 범위" }})의 연속 실제 분기 ${usable.size}개만 사용했습니다. ")
            append("최근 4분기 매출 방향은 $revenueTrend")
            if (marginValues.size >= 3) append(", 영업이익률 방향은 $marginTrend")
            append("입니다. ")

            append("최신 ${latest.period} 매출은 ${formatAmount(latest.revenue)}")
            latestYoY?.let { append(", 전년동기 대비 ${formatPct(it)}") }
            latestQoQ?.let { append(", 전분기 대비 ${formatPct(it)}") }
            append("입니다. ")
            latestMargin?.let { append("최신 영업이익률은 ${formatPct(it)}입니다. ") }

            if (latestYoY != null && previousYoY != null) {
                val delta = latestYoY - previousYoY
                when {
                    delta >= 3.0 -> append("매출 YoY 성장률은 직전 분기보다 ${formatPointDelta(delta)} 높아져 성장 속도가 가속됐습니다. ")
                    delta <= -3.0 -> append("매출 YoY 성장률은 직전 분기보다 ${formatPointDelta(delta)} 낮아져 성장 속도가 둔화됐습니다. ")
                    else -> append("매출 YoY 성장률 변화는 ${formatPointDelta(delta)}로 성장 속도는 대체로 유지되고 있습니다. ")
                }
            }

            if (usable.size >= 8) {
                append(eightQuarterComparison(usable.takeLast(8)))
                append(" ")
            }

            append(inflectionSentence(usable))
            append(" ")
            append(qualitySentence(history))
            append(" OpenDART의 실제 3개월 값을 우선하고, 직접 분기값이 없을 때만 같은 CFS/OFS 범위의 누적값 차감으로 복원합니다.")
        }
    }

    fun compactSignal(history: QuarterlyHistory): String {
        if (!history.loaded) return "시계열 미확인"
        val points = history.comparablePoints
        if (points.size < 4) return "연속 시계열 근거 부족"
        val revenue = direction(points.takeLast(4).mapNotNull { it.revenue })
        val margins = points.takeLast(4).mapNotNull { it.operatingMargin }
        val margin = if (margins.size >= 3) direction(margins) else "마진 확인 부족"
        val acceleration = if (points.size >= 5) {
            val latest = reliableYoY(points, points.lastIndex)
            val previous = reliableYoY(points, points.lastIndex - 1)
            if (latest != null && previous != null) {
                when {
                    latest - previous >= 3.0 -> "성장 가속"
                    latest - previous <= -3.0 -> "성장 둔화"
                    else -> "성장속도 유지"
                }
            } else null
        } else null
        return listOfNotNull("매출 $revenue", "마진 $margin", acceleration).joinToString(" · ")
    }

    private fun eightQuarterComparison(points: List<QuarterlyPoint>): String {
        if (points.size < 8) return ""
        val prior4 = points.take(4)
        val recent4 = points.takeLast(4)
        val priorRevenue = prior4.mapNotNull { it.revenue }.takeIf { it.size == 4 }?.sum()
        val recentRevenue = recent4.mapNotNull { it.revenue }.takeIf { it.size == 4 }?.sum()
        val aggregateGrowth = if (priorRevenue != null && recentRevenue != null) safePct(recentRevenue, priorRevenue) else null
        val priorMargin = weightedMargin(prior4)
        val recentMargin = weightedMargin(recent4)

        return buildString {
            if (aggregateGrowth != null) {
                append("최근 4개 분기의 합산 매출은 직전 4개 분기보다 ${formatPct(aggregateGrowth)}")
                append(if (aggregateGrowth >= 0) " 커졌습니다." else " 줄었습니다.")
            } else {
                append("최근 4개 분기와 직전 4개 분기의 합산 매출 비교는 보류합니다.")
            }
            if (priorMargin != null && recentMargin != null) {
                val delta = recentMargin - priorMargin
                append(" 같은 두 구간의 매출가중 영업이익률은 ${formatPct(priorMargin)} → ${formatPct(recentMargin)}로 ${formatPointDelta(delta)} 변했습니다.")
                when {
                    aggregateGrowth != null && aggregateGrowth > 5.0 && delta > 1.0 -> append(" 외형 성장과 수익성 개선이 함께 나타나는 조합입니다.")
                    aggregateGrowth != null && aggregateGrowth > 5.0 && delta < -1.0 -> append(" 외형은 커졌지만 수익성은 희석돼 성장의 질을 추가 확인해야 합니다.")
                    aggregateGrowth != null && aggregateGrowth < -5.0 && delta > 1.0 -> append(" 외형 축소 속에서도 수익성 방어가 나타나 비용절감·믹스 효과 여부를 확인해야 합니다.")
                    aggregateGrowth != null && aggregateGrowth < -5.0 && delta < -1.0 -> append(" 외형과 수익성이 동시에 약해져 수요·원가 양쪽의 악화 여부를 확인해야 합니다.")
                }
            }
        }
    }

    private fun reliableYoY(points: List<QuarterlyPoint>, index: Int): Double? {
        if (index !in points.indices) return null
        val current = points[index]
        current.revenueYoY?.takeIf { it.isFinite() && abs(it) <= 100000.0 }?.let { return it }
        if (index < 4) return null
        val prior = points[index - 4]
        if (current.ordinal - prior.ordinal != 4 || current.scope != prior.scope) return null
        val currentRevenue = current.revenue ?: return null
        val priorRevenue = prior.revenue ?: return null
        return safePct(currentRevenue, priorRevenue)
    }

    private fun reliableQoQ(points: List<QuarterlyPoint>): Double? {
        if (points.size < 2) return null
        val latest = points.last()
        val previous = points[points.lastIndex - 1]
        if (latest.ordinal - previous.ordinal != 1 || latest.scope != previous.scope) return null
        val latestRevenue = latest.revenue ?: return null
        val previousRevenue = previous.revenue ?: return null
        return safePct(latestRevenue, previousRevenue)
    }

    private fun weightedMargin(points: List<QuarterlyPoint>): Double? {
        if (points.size != 4) return null
        if (points.any { it.revenue == null || it.operatingIncome == null }) return null
        val revenue = points.sumOf { it.revenue!! }
        if (revenue <= 0.0) return null
        val operatingIncome = points.sumOf { it.operatingIncome!! }
        val margin = operatingIncome / revenue * 100.0
        return margin.takeIf { it.isFinite() && abs(it) <= 1000.0 }
    }

    private fun qualitySentence(history: QuarterlyHistory): String {
        val total = history.availableQuarterCount
        val direct = history.directQuarterCount
        val reconstructed = history.reconstructedQuarterCount
        return when {
            total == 0 -> "분기 산출 품질을 확인할 실제 값이 없습니다."
            reconstructed == 0 -> "사용한 $total개 분기는 모두 직접 3개월 값입니다."
            else -> "사용한 $total개 중 직접 3개월 값은 ${direct}개, 같은 회계범위의 누적 차감으로 복원한 값은 ${reconstructed}개입니다."
        }
    }

    private fun inflectionSentence(points: List<QuarterlyPoint>): String {
        val margins = points.filter { it.operatingMargin != null }.takeLast(4)
        if (margins.size >= 3) {
            val a = margins[margins.lastIndex - 2].operatingMargin!!
            val b = margins[margins.lastIndex - 1].operatingMargin!!
            val c = margins.last().operatingMargin!!
            when {
                a > b && c > b -> return "마진은 최근 저점을 찍고 반등하는 변곡 신호가 있습니다."
                a < b && c < b -> return "마진 개선 흐름은 최근 분기에서 꺾였습니다."
            }
        }
        val revenue = points.takeLast(4).mapNotNull { it.revenue }
        if (revenue.size >= 3) {
            val a = revenue[revenue.lastIndex - 2]
            val b = revenue[revenue.lastIndex - 1]
            val c = revenue.last()
            when {
                a > b && c > b -> return "매출은 최근 분기에서 저점 반등 형태가 나타납니다."
                a < b && c < b -> return "매출은 최근 분기에서 상승 흐름이 둔화됐습니다."
            }
        }
        return "뚜렷한 단기 변곡은 아직 확인되지 않습니다."
    }

    private fun direction(values: List<Double>): String {
        if (values.size < 2) return "판단 보류"
        var up = 0
        var down = 0
        values.zipWithNext().forEach { (a, b) ->
            val scale = maxOf(abs(a), abs(b), 1.0)
            val delta = (b - a) / scale
            if (delta > 0.02) up++ else if (delta < -0.02) down++
        }
        return when {
            up >= 2 && up > down -> "상승"
            down >= 2 && down > up -> "하락"
            up > down -> "완만한 개선"
            down > up -> "완만한 약화"
            else -> "횡보·혼조"
        }
    }

    private fun safePct(current: Double, prior: Double): Double? {
        if (prior <= 0.0) return null
        val value = (current / prior - 1.0) * 100.0
        return value.takeIf { it.isFinite() && abs(it) <= 100000.0 }
    }

    fun formatPct(value: Double?): String = value?.let { String.format("%+.1f%%", it) } ?: "N/A"

    private fun formatPointDelta(value: Double): String = String.format("%+.1f%%p", value)

    fun formatAmount(value: Double?): String {
        if (value == null) return "N/A"
        val absolute = abs(value)
        return when {
            absolute >= 1_000_000_000_000.0 -> String.format("%.2f조원", value / 1_000_000_000_000.0)
            absolute >= 100_000_000.0 -> String.format("%.0f억원", value / 100_000_000.0)
            absolute >= 10_000.0 -> String.format("%.0f만원", value / 10_000.0)
            else -> String.format("%.0f원", value)
        }
    }
}

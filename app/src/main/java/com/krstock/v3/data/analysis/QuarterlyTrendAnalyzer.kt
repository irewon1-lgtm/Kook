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
                available >= 8 -> "8/8분기 확인"
                available >= 4 -> "$available/8분기 확인"
                else -> "$available/8분기 · 판독 보류"
            }
        )
    }

    fun summarize(history: QuarterlyHistory): String {
        if (!history.loaded) return "4~8분기 시계열을 불러오는 중입니다."
        val usable = history.points.filter { it.revenue != null }
        if (usable.size < 4) {
            return "비교 가능한 실제 분기 매출이 ${usable.size}개라 추세 판독을 보류합니다. 결측 분기를 추정으로 메우지 않습니다."
        }

        val latest = usable.last()
        val last4 = usable.takeLast(4)
        val revenueTrend = direction(last4.mapNotNull { it.revenue })
        val marginSeries = history.points.filter { it.operatingMargin != null }
        val marginTrend = direction(marginSeries.takeLast(4).mapNotNull { it.operatingMargin })
        val yoy = latest.revenueYoY?.let { formatPct(it) }
        val qoq = latest.revenueQoQ?.let { formatPct(it) }
        val latestMargin = latest.operatingMargin?.let { formatPct(it) }
        val fourQuarterAgo = history.points
            .filter { it.revenue != null }
            .dropLast(1)
            .lastOrNull { it.fiscalYear == latest.fiscalYear - 1 && it.quarter == latest.quarter }
        val marginDelta = if (latest.operatingMargin != null && fourQuarterAgo?.operatingMargin != null) {
            latest.operatingMargin - fourQuarterAgo.operatingMargin
        } else null

        return buildString {
            append("실제 분기 ${usable.size}개를 기준으로 보면 매출 흐름은 $revenueTrend")
            if (marginSeries.size >= 3) append(", 영업이익률 흐름은 $marginTrend")
            append("입니다. ")
            append("최신 ${latest.period} 매출은 ${formatAmount(latest.revenue)}")
            if (yoy != null) append(", 전년동기 대비 $yoy")
            if (qoq != null) append(", 전분기 대비 $qoq")
            append("입니다. ")
            if (latestMargin != null) append("영업이익률은 $latestMargin")
            if (marginDelta != null) append("이며 1년 전 같은 분기보다 ${formatPointDelta(marginDelta)}")
            if (latestMargin != null) append("입니다. ")
            append(inflectionSentence(history.points))
            append(" 이 시계열은 OpenDART 실제 손익계산서에서 직접 3개월 값을 우선 사용하고, 직접값이 없을 때만 같은 CFS/OFS 범위의 누적 차감으로 복원합니다.")
        }
    }

    fun compactSignal(history: QuarterlyHistory): String {
        if (!history.loaded) return "시계열 미확인"
        val revenue = history.points.mapNotNull { it.revenue }
        val margin = history.points.mapNotNull { it.operatingMargin }
        if (revenue.size < 4) return "시계열 근거 부족"
        val r = direction(revenue.takeLast(4))
        val m = if (margin.size >= 3) direction(margin.takeLast(4)) else "마진 확인 부족"
        return "최근 매출 $r · 영업이익률 $m"
    }

    private fun inflectionSentence(points: List<QuarterlyPoint>): String {
        val margins = points.filter { it.operatingMargin != null }.takeLast(4)
        if (margins.size >= 3) {
            val a = margins[margins.lastIndex - 2].operatingMargin!!
            val b = margins[margins.lastIndex - 1].operatingMargin!!
            val c = margins.last().operatingMargin!!
            when {
                a > b && c > b -> return "최근에는 마진이 저점을 찍고 반등하는 변곡 신호가 있습니다."
                a < b && c < b -> return "최근에는 마진 개선이 꺾이는 변곡 신호가 있습니다."
            }
        }
        val revenue = points.filter { it.revenue != null }.takeLast(4)
        if (revenue.size >= 3) {
            val a = revenue[revenue.lastIndex - 2].revenue!!
            val b = revenue[revenue.lastIndex - 1].revenue!!
            val c = revenue.last().revenue!!
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

    fun formatPct(value: Double?): String = value?.let { String.format("%+.1f%%", it) } ?: "N/A"

    private fun formatPointDelta(value: Double): String = String.format("%+.1f%%p", value)

    fun formatAmount(value: Double?): String {
        if (value == null) return "N/A"
        val absolute = kotlin.math.abs(value)
        return when {
            absolute >= 1_000_000_000_000.0 -> String.format("%.2f조원", value / 1_000_000_000_000.0)
            absolute >= 100_000_000.0 -> String.format("%.0f억원", value / 100_000_000.0)
            absolute >= 10_000.0 -> String.format("%.0f만원", value / 10_000.0)
            else -> String.format("%.0f원", value)
        }
    }
}

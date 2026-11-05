package com.krstock.v3.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.price.PriceAdjustmentProvenance
import com.krstock.v3.data.price.PriceHistoryAggregation
import com.krstock.v3.data.price.PriceHistoryAggregation.Candle
import com.krstock.v3.data.price.PriceHistoryAggregation.Interval
import com.krstock.v3.data.price.PriceHistoryRepository
import java.time.format.DateTimeFormatter
import kotlin.math.max

private val ChartRise = Color(0xFFFF3B45)
private val ChartFall = Color(0xFF178CFF)

/** Real OHLCV calendar candles, independent of the KR4 ranking engine. */
@Composable
fun PriceChartPanel(
    result: PriceHistoryRepository.Result?,
    loading: Boolean,
    initialCandleMode: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var months by rememberSaveable { mutableIntStateOf(6) }
    var candleMode by rememberSaveable { mutableStateOf(initialCandleMode) }
    var interval by rememberSaveable { mutableStateOf(Interval.DAY) }
    val scheme = MaterialTheme.colorScheme
    val adjustment = result?.adjustment ?: PriceAdjustmentProvenance()
    val bars = remember(result, months, interval) {
        PriceHistoryAggregation.visible(result?.bars.orEmpty(), interval, months)
    }
    // The quote always describes the latest observed day, even when the chart
    // below displays calendar weeks or months.
    val daily = remember(result) {
        PriceHistoryAggregation.aggregate(result?.bars.orEmpty(), Interval.DAY)
    }

    Column(
        modifier = modifier.fillMaxWidth().testTag("price_chart_panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LatestPriceQuote(daily)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = scheme.surface),
            border = BorderStroke(1.dp, scheme.outlineVariant),
        ) {
            Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(3.dp).height(18.dp)
                                .background(scheme.primary, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("주가 차트", fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp)
                    }
                    Surface(
                        color = Color.Transparent,
                        contentColor = scheme.onSurfaceVariant,
                        shape = RoundedCornerShape(9.dp),
                        border = BorderStroke(1.dp, scheme.outline),
                        modifier = Modifier.testTag("chart_adjustment_notice"),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("⚠", fontSize = 12.sp, lineHeight = 14.sp)
                            Text(
                                adjustment.label, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.testTag("chart_adjustment_status"),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Interval.entries.forEach { item ->
                        ChartChoiceChip(
                            selected = interval == item,
                            onClick = { interval = item },
                            label = item.label,
                            modifier = Modifier.width(58.dp).testTag("chart_interval_${item.name}"),
                        )
                    }
                }
                Row(
                    modifier = Modifier.widthIn(
                        max = (236f * LocalConfiguration.current.fontScale.coerceAtLeast(1f)).dp,
                    ).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    listOf(1 to "1개월", 3 to "3개월", 6 to "6개월", 12 to "1년").forEach { (period, label) ->
                        ChartChoiceChip(
                            selected = months == period,
                            onClick = { months = period },
                            label = label,
                            modifier = Modifier.weight(1f).testTag("chart_period_$period"),
                        )
                    }
                }
                Row(
                    modifier = Modifier.height(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(if (candleMode) "캔들차트" else "선차트", fontSize = 11.sp, lineHeight = 14.sp)
                    Switch(
                        checked = candleMode,
                        onCheckedChange = { candleMode = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = scheme.primary,
                            checkedBorderColor = scheme.primary,
                            uncheckedThumbColor = scheme.onSurfaceVariant,
                            uncheckedTrackColor = scheme.surfaceVariant,
                            uncheckedBorderColor = scheme.outline,
                        ),
                        modifier = Modifier.height(28.dp).scale(0.82f).testTag("chart_candle_toggle"),
                    )
                }

                if (loading && result == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(if (LocalConfiguration.current.screenWidthDp <= 420) 180.dp else 252.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("일별 주가 불러오는 중", fontSize = 12.sp)
                    }
                } else if (bars.isEmpty()) {
                    Text(
                        result?.detail ?: "조회된 차트가 없습니다.",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 50.dp),
                        fontSize = 12.sp, color = scheme.onSurfaceVariant,
                    )
                } else {
                    val first = bars.first()
                    val last = bars.last()
                    val high = bars.maxOf { if (candleMode) it.high else it.close }
                    val low = bars.minOf { if (candleMode) it.low else it.close }
                    val range = max(0.01, high - low)
                    ObservedPricePlot(bars, candleMode, high, range)
                    ObservedDateAxis(bars)
                    Surface(
                        color = scheme.surfaceVariant.copy(alpha = 0.52f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, scheme.outlineVariant),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(
                                        if (months == 12) "1년 기준" else "${months}개월 기준",
                                        fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold,
                                    )
                                    val shortDate = DateTimeFormatter.ofPattern("MM.dd")
                                    Text(
                                        "${first.firstTradingDate.format(shortDate)} ~ ${last.lastTradingDate.format(shortDate)}",
                                        fontSize = 8.sp, lineHeight = 10.sp, color = scheme.onSurfaceVariant,
                                    )
                                    Text("최근 ${interval.label}", fontSize = 8.sp, lineHeight = 10.sp,
                                        color = scheme.onSurfaceVariant)
                                }
                                VerticalDivider(Modifier.height(35.dp), color = scheme.outlineVariant)
                                Spacer(Modifier.width(8.dp))
                                val ohlcText = "시가 ${priceText(last.open)} · 고가 ${priceText(last.high)} · " +
                                    "저가 ${priceText(last.low)} · 종가 ${priceText(last.close)}"
                                Row(
                                    modifier = Modifier.weight(3.75f).clearAndSetSemantics {
                                        testTag = "chart_last_ohlc"
                                        text = AnnotatedString(ohlcText)
                                    },
                                ) {
                                    listOf("시가" to last.open, "고가" to last.high, "저가" to last.low, "종가" to last.close)
                                        .forEach { (label, price) ->
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(label, fontSize = 9.sp, lineHeight = 12.sp, color = scheme.onSurfaceVariant)
                                                Text(priceText(price), fontSize = 10.sp, lineHeight = 14.sp,
                                                    fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                }
                            }
                            HorizontalDivider(color = scheme.outlineVariant)
                            Text(
                                "최근 ${interval.label} · 제공 원본 OHLC${if (interval != Interval.DAY) " 집계" else ""}",
                                fontSize = 8.sp, lineHeight = 10.sp, color = scheme.onSurfaceVariant,
                            )
                            Text(
                                "거래량 ${last.volume?.let { String.format("%,d주", it) } ?: "미확보"} · 실제 ${last.tradingDays}거래일",
                                modifier = Modifier.testTag("chart_last_volume"),
                                fontSize = 10.sp, lineHeight = 14.sp, color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "ⓘ 데이터 출처: 네이버증권 · " +
                        (result?.source?.name ?: "조회 대기") +
                        " · 기준 " + (result?.priceCutoffDate?.toString() ?: "-"),
                    modifier = Modifier.testTag("chart_source"),
                    fontSize = 9.sp, lineHeight = 12.sp, color = scheme.onSurfaceVariant,
                )
            }
        }
        Column(
            Modifier.padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (bars.isNotEmpty()) {
                val first = bars.first()
                val last = bars.last()
                val high = bars.maxOf { if (candleMode) it.high else it.close }
                val low = bars.minOf { if (candleMode) it.low else it.close }
                val change = (last.close / first.close - 1.0) * 100.0
                Text(
                    "최저 " + String.format("%,.0f", low) + "원 · 최고 " +
                        String.format("%,.0f", high) + "원 · " + bars.size + "개 ${interval.label}",
                    modifier = Modifier.testTag("chart_bar_count"),
                    fontSize = 10.sp, lineHeight = 14.sp, color = scheme.onSurfaceVariant,
                )
                Text(String.format("선택 기간 원본 가격 변동 %+.2f%%", change),
                    fontSize = 10.sp, lineHeight = 14.sp, color = if (change >= 0) ChartRise else ChartFall)
                Text(
                    "${first.firstTradingDate} ~ ${last.lastTradingDate} · 제공 원본 OHLC${if (interval != Interval.DAY) " 집계" else ""}",
                    fontSize = 10.sp, lineHeight = 14.sp, color = scheme.onSurfaceVariant,
                )
                if (interval != Interval.DAY) {
                    Text(
                        "시작 ${interval.label}은 첫 거래일부터 집계합니다. " +
                            if (result != null && last.periodEnd > result.priceCutoffDate)
                                "마지막 ${interval.label}은 기준일까지의 부분 봉입니다." else "",
                        modifier = Modifier.testTag("chart_aggregation_note"),
                        fontSize = 10.sp, lineHeight = 14.sp, color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (bars.isNotEmpty()) {
                Text(result?.detail ?: "조회 대기", modifier = Modifier.testTag("chart_source_detail"),
                    fontSize = 10.sp, lineHeight = 14.sp, color = scheme.onSurfaceVariant)
            }
            Text(
                adjustment.reason,
                modifier = Modifier.testTag("chart_adjustment_reason"),
                fontSize = 10.sp, color = scheme.onSurfaceVariant,
            )
            Text(adjustment.treatment, fontSize = 10.sp, color = scheme.onSurfaceVariant)
            Text(
                adjustment.sourceDetail + "\n" + adjustment.priceSourceUrl,
                modifier = Modifier.testTag("chart_adjustment_source"),
                fontSize = 10.sp, color = scheme.onSurfaceVariant,
            )
            Text(
                "분할·병합 전후의 가격 변동에는 기업행사 영향이 포함될 수 있습니다. " +
                    "결측 거래일은 임의로 채우지 않습니다. 4지표 순위와 별도 참고자료입니다.",
                fontSize = 10.sp, color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChartChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = if (selected) scheme.primary.copy(alpha = 0.36f) else Color.Transparent,
        contentColor = if (selected) Color.White else scheme.onSurface,
        border = BorderStroke(1.dp, if (selected) scheme.primary else scheme.outline),
        modifier = modifier.height(28.dp)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
            .semantics { this.selected = selected },
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center,
                maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun LatestPriceQuote(daily: List<Candle>) {
    val scheme = MaterialTheme.colorScheme
    val last = daily.lastOrNull()
    val previous = daily.dropLast(1).lastOrNull()
    Card(
        modifier = Modifier.fillMaxWidth().testTag("chart_latest_quote"),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2440)),
        border = BorderStroke(1.dp, Color(0xFF2B5178)),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(Color(0xFF103359), Color(0xFF0C213A))))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (last == null) {
                Text("최근 거래 가격", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                Text("가격 자료 대기", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            "${priceText(last.close)}원", fontSize = 22.sp, lineHeight = 24.sp,
                            fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.testTag("chart_latest_close"),
                        )
                        if (previous != null) {
                            val delta = last.close - previous.close
                            val change = (last.close / previous.close - 1.0) * 100.0
                            Text(
                                (if (delta < 0) "▼ " else if (delta > 0) "▲ " else "") +
                                    String.format("%+,.0f원 (%+.2f%%)", delta, change),
                                fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = if (delta >= 0) ChartRise else ChartFall,
                                modifier = Modifier.testTag("chart_latest_change"),
                            )
                        } else {
                            Text("전 거래일 비교자료 미확보", fontSize = 9.sp, lineHeight = 12.sp, color = scheme.onSurfaceVariant)
                        }
                    }
                    VerticalDivider(Modifier.height(34.dp), color = Color(0xFF36516C))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            QuoteValue("시가", last.open)
                            QuoteValue("고가", last.high)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            QuoteValue("저가", last.low)
                            QuoteValue("종가", last.close)
                        }
                    }
                }
                Text(
                    "${last.lastTradingDate} · 거래량 ${last.volume?.let { String.format("%,d주", it) } ?: "미확보"}",
                    fontSize = 8.sp, lineHeight = 10.sp, color = Color(0xFFB3C6D9),
                    modifier = Modifier.testTag("chart_latest_volume"),
                )
            }
        }
    }
}

@Composable
private fun QuoteValue(label: String, price: Double) {
    Text("$label ${priceText(price)}", fontSize = 9.sp, lineHeight = 13.sp, color = Color(0xFFE4ECF5))
}

@Composable
private fun ObservedPricePlot(bars: List<Candle>, candleMode: Boolean, high: Double, range: Double) {
    val scheme = MaterialTheme.colorScheme
    val last = bars.last()
    val lineColor = scheme.primary
    val gridColor = scheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(if (LocalConfiguration.current.screenWidthDp <= 420) 180.dp else 252.dp)) {
        Canvas(modifier = Modifier.weight(1f).fillMaxHeight().testTag("historical_price_chart")) {
            val paddingX = 7.dp.toPx()
            val paddingY = 10.dp.toPx()
            val usableWidth = (size.width - paddingX * 2).coerceAtLeast(1f)
            val usableHeight = (size.height - paddingY * 2).coerceAtLeast(1f)
            val count = bars.size
            fun priceY(value: Double): Float =
                paddingY + (((high - value) / range) * usableHeight).toFloat()
            fun pointX(index: Int): Float =
                paddingX + if (count < 2) usableWidth / 2 else index.toFloat() / (count - 1) * usableWidth

            for (i in 0..4) {
                val y = paddingY + i * usableHeight / 4f
                drawLine(
                    gridColor, Offset(paddingX, y),
                    Offset(size.width - paddingX, y), strokeWidth = 1.dp.toPx(),
                )
                val x = paddingX + i * usableWidth / 4f
                drawLine(
                    gridColor.copy(alpha = 0.55f), Offset(x, paddingY),
                    Offset(x, size.height - paddingY), strokeWidth = 0.5.dp.toPx(),
                )
            }
            if (candleMode) {
                val barWidth = chartCandleWidth(usableWidth, count, paddingX, 1.dp.toPx())
                bars.forEachIndexed { i, bar ->
                    val x = pointX(i)
                    val color = if (bar.close >= bar.open) ChartRise else ChartFall
                    drawLine(
                        color, Offset(x, priceY(bar.high)),
                        Offset(x, priceY(bar.low)), strokeWidth = 1.dp.toPx(),
                    )
                    val top = priceY(maxOf(bar.open, bar.close))
                    val bottom = priceY(minOf(bar.open, bar.close))
                    drawRect(
                        color, topLeft = Offset(x - barWidth / 2f, top),
                        size = Size(barWidth, (bottom - top).coerceAtLeast(1.dp.toPx())),
                    )
                }
            } else {
                val path = Path()
                bars.forEachIndexed { i, bar ->
                    val x = pointX(i)
                    val y = priceY(bar.close)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
                drawCircle(
                    color = lineColor, radius = 3.dp.toPx(),
                    center = Offset(pointX(count - 1), priceY(last.close)),
                )
            }
        }
        Column(
            modifier = Modifier.width(42.dp).fillMaxHeight().padding(vertical = 3.dp)
                .testTag("chart_price_axis"),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            for (i in 0..4) {
                Text(
                    priceText(high - i * range / 4.0), fontSize = 8.sp,
                    color = scheme.onSurfaceVariant, lineHeight = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun ObservedDateAxis(bars: List<Candle>) {
    val dates = (0..5).map { tick -> bars.lastIndex * tick / 5 }.distinct()
    val dateFormat = DateTimeFormatter.ofPattern("MM-dd")
    Row(Modifier.fillMaxWidth().testTag("chart_date_axis")) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            dates.forEach { index ->
                Text(
                    bars[index].lastTradingDate.format(dateFormat),
                    fontSize = 8.sp, lineHeight = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(42.dp))
    }
}

private fun priceText(value: Double): String = String.format("%,.0f", value)

internal fun chartCandleWidth(usableWidth: Float, count: Int, padding: Float, minimum: Float): Float {
    require(count > 0 && usableWidth > 0 && padding * 2 >= minimum)
    // Endpoints sit one padding-width inside the canvas. A sparse monthly
    // series must not make their candle bodies wider than that available inset.
    return (usableWidth / count * 0.66f).coerceIn(minimum, padding * 2)
}

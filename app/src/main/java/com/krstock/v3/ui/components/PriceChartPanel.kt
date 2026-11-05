package com.krstock.v3.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.price.PriceHistoryRepository
import com.krstock.v3.data.price.PriceHistoryAggregation
import com.krstock.v3.data.price.PriceHistoryAggregation.Interval
import com.krstock.v3.data.price.PriceAdjustmentProvenance
import java.time.format.DateTimeFormatter
import kotlin.math.max

/** Real OHLCV calendar candles, independent of the KR4 ranking engine. */
@Composable
fun PriceChartPanel(
    result: PriceHistoryRepository.Result?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    var months by rememberSaveable { mutableIntStateOf(6) }
    var candleMode by rememberSaveable { mutableStateOf(false) }
    var interval by rememberSaveable { mutableStateOf(Interval.DAY) }
    val scheme = MaterialTheme.colorScheme
    val adjustment = result?.adjustment ?: PriceAdjustmentProvenance()
    val bars = remember(result, months, interval) {
        PriceHistoryAggregation.visible(result?.bars.orEmpty(), interval, months)
    }

    Card(
        modifier = modifier.fillMaxWidth().testTag("price_chart_panel"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("과거 주가 차트", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(
                "실제 일봉에서 집계 · 4지표 순위와 별도 참고자료",
                fontSize = 11.sp, color = scheme.onSurfaceVariant,
            )
            Surface(
                color = scheme.tertiaryContainer,
                contentColor = scheme.onTertiaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().testTag("chart_adjustment_notice"),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(adjustment.label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("chart_adjustment_status"))
                    Text(adjustment.reason, fontSize = 11.sp,
                        modifier = Modifier.testTag("chart_adjustment_reason"))
                    Text(adjustment.treatment, fontSize = 11.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Interval.entries.forEach { item ->
                    FilterChip(
                        selected = interval == item,
                        onClick = { interval = item },
                        label = { Text(item.label, fontSize = 11.sp) },
                        modifier = Modifier.testTag("chart_interval_${item.name}"),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "1개월", 3 to "3개월", 6 to "6개월", 12 to "1년").forEach { (period, label) ->
                    FilterChip(
                        selected = months == period,
                        onClick = { months = period },
                        label = { Text(label, fontSize = 11.sp) },
                        modifier = Modifier.testTag("chart_period_$period"),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (candleMode) "캔들차트" else "선차트", fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = candleMode,
                    onCheckedChange = { candleMode = it },
                    modifier = Modifier.testTag("chart_candle_toggle"),
                )
            }

            if (loading && result == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(190.dp),
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
                val change = (last.close / first.close - 1.0) * 100.0

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(String.format("%,.0f원", last.close), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(
                            String.format("선택 기간 원본 가격 변동 %+.2f%%", change),
                            fontSize = 11.sp,
                            color = if (change >= 0) Color(0xFFC62828) else Color(0xFF1976D2),
                        )
                    }
                }

                Text(
                    "시가 ${priceText(last.open)} · 고가 ${priceText(last.high)} · " +
                        "저가 ${priceText(last.low)} · 종가 ${priceText(last.close)}",
                    modifier = Modifier.testTag("chart_last_ohlc"),
                    fontSize = 11.sp, color = scheme.onSurfaceVariant,
                )
                Text(
                    "거래량 ${last.volume?.let { String.format("%,d주", it) } ?: "미확보"} · 실제 ${last.tradingDays}거래일",
                    modifier = Modifier.testTag("chart_last_volume"),
                    fontSize = 11.sp, color = scheme.onSurfaceVariant,
                )

                val lineColor = scheme.primary
                val gridColor = scheme.outlineVariant
                val riseColor = Color(0xFFC62828)
                val fallColor = Color(0xFF1565C0)
                Canvas(modifier = Modifier.fillMaxWidth().height(224.dp).testTag("historical_price_chart")) {
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
                    }
                    if (candleMode) {
                        val barWidth = chartCandleWidth(usableWidth, count, paddingX, 1.dp.toPx())
                        bars.forEachIndexed { i, bar ->
                            val x = pointX(i)
                            val color = if (bar.close >= bar.open) riseColor else fallColor
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

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(first.firstTradingDate.format(DateTimeFormatter.ISO_DATE), fontSize = 10.sp, color = scheme.onSurfaceVariant)
                    Text(last.lastTradingDate.format(DateTimeFormatter.ISO_DATE), fontSize = 10.sp, color = scheme.onSurfaceVariant)
                }
                Text(
                    "최저 " + String.format("%,.0f", low) + "원 · 최고 " +
                        String.format("%,.0f", high) + "원 · " + bars.size + "개 ${interval.label}",
                    modifier = Modifier.testTag("chart_bar_count"),
                    fontSize = 11.sp, color = scheme.onSurfaceVariant,
                )
                if (interval != Interval.DAY) {
                    Text(
                        "시작 ${interval.label}은 첫 거래일부터 집계합니다. " +
                            if (result != null && last.periodEnd > result.priceCutoffDate)
                                "마지막 ${interval.label}은 기준일까지의 부분 봉입니다." else "",
                        modifier = Modifier.testTag("chart_aggregation_note"),
                        fontSize = 10.sp, color = scheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "출처: 네이버증권 · " +
                    (result?.let { "${it.source.name} · ${it.detail}" }
                        ?: "조회 대기") +
                    " · 기준 " + (result?.priceCutoffDate?.toString() ?: "-"),
                modifier = Modifier.testTag("chart_source"),
                fontSize = 10.sp, color = scheme.onSurfaceVariant,
            )
            Text(
                adjustment.sourceDetail + "\n" + adjustment.priceSourceUrl,
                modifier = Modifier.testTag("chart_adjustment_source"),
                fontSize = 10.sp, color = scheme.onSurfaceVariant,
            )
            Text(
                "분할·병합 전후의 가격 변동에는 기업행사 영향이 포함될 수 있습니다. " +
                    "결측 거래일은 임의로 채우지 않습니다.",
                fontSize = 10.sp, color = scheme.onSurfaceVariant,
            )
        }
    }
}

private fun priceText(value: Double): String = String.format("%,.0f", value)

internal fun chartCandleWidth(usableWidth: Float, count: Int, padding: Float, minimum: Float): Float {
    require(count > 0 && usableWidth > 0 && padding * 2 >= minimum)
    // Endpoints sit one padding-width inside the canvas. A sparse monthly
    // series must not make their candle bodies wider than that available inset.
    return (usableWidth / count * 0.66f).coerceIn(minimum, padding * 2)
}

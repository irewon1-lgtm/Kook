package com.krstock.v3.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import java.time.format.DateTimeFormatter
import kotlin.math.max

/** Historical daily close/candle view, independent of the KR4 ranking engine. */
@Composable
fun PriceChartPanel(
    result: PriceHistoryRepository.Result?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    var months by remember { mutableIntStateOf(6) }
    var candleMode by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val bars = remember(result, months) {
        val all = result?.bars.orEmpty()
        val latest = all.lastOrNull()?.date
        if (latest == null) emptyList()
        else all.filter { !it.date.isBefore(latest.minusMonths(months.toLong())) }
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
                "마감 종가 기준 · 4지표 순위와 별도 참고자료",
                fontSize = 11.sp, color = scheme.onSurfaceVariant,
            )
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
            } else if (bars.size < 2) {
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
                            String.format("선택 기간 %+.2f%%", change),
                            fontSize = 11.sp,
                            color = if (change >= 0) Color(0xFFC62828) else Color(0xFF1976D2),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("캔들", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Switch(
                            checked = candleMode,
                            onCheckedChange = { candleMode = it },
                            modifier = Modifier.testTag("chart_candle_toggle"),
                        )
                    }
                }

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
                        paddingX + if (count < 2) 0f else index.toFloat() / (count - 1) * usableWidth

                    for (i in 0..4) {
                        val y = paddingY + i * usableHeight / 4f
                        drawLine(
                            gridColor, Offset(paddingX, y),
                            Offset(size.width - paddingX, y), strokeWidth = 1.dp.toPx(),
                        )
                    }
                    if (candleMode) {
                        val barWidth = (usableWidth / count * 0.66f).coerceAtLeast(1.dp.toPx())
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
                    Text(first.date.format(DateTimeFormatter.ISO_DATE), fontSize = 10.sp, color = scheme.onSurfaceVariant)
                    Text(last.date.format(DateTimeFormatter.ISO_DATE), fontSize = 10.sp, color = scheme.onSurfaceVariant)
                }
                Text(
                    "최저 " + String.format("%,.0f", low) + "원 · 최고 " +
                        String.format("%,.0f", high) + "원 · " + bars.size + "거래일",
                    fontSize = 11.sp, color = scheme.onSurfaceVariant,
                )
            }
            Text(
                "출처: 네이버증권 · " +
                    (result?.let { if (it.source == PriceHistoryRepository.Source.LIVE) "일봉 수신" else it.detail }
                        ?: "조회 대기") +
                    " · 기준 " + (result?.priceCutoffDate?.toString() ?: "-"),
                fontSize = 10.sp, color = scheme.onSurfaceVariant,
            )
            Text(
                "과거 원시 주가입니다. 액면분할·병합 등 기업행사에 따른 수정주가가 아닐 수 있습니다. " +
                    "결측 거래일은 임의로 채우지 않습니다.",
                fontSize = 10.sp, color = scheme.onSurfaceVariant,
            )
        }
    }
}

package com.krstock.v3.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.candidate.FinalCandidateRepository
import com.krstock.v3.data.model.FinancialSafetyRecord
import com.krstock.v3.data.model.MetricValue
import com.krstock.v3.data.model.StockSummary
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.stage.FinancialSafetyRepository
import com.krstock.v3.data.stage.ValuationBandPolicy
import com.krstock.v3.data.update.SnapshotAutoUpdater
import com.krstock.v3.ui.components.FinancialSafetyBadge
import com.krstock.v3.ui.components.Stage45CompactPanel
import com.krstock.v3.ui.components.StatusBadge
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val LocalStockRowMetrics = staticCompositionLocalOf { true }

@Composable
fun HomeScreen(onStockClick: (String) -> Unit, onNavigateToList: () -> Unit, onSearch: (() -> Unit)? = null) {
    val snapshotState by SnapshotAutoUpdater.state.collectAsState()
    val stocks = remember(snapshotState.revision) { StockRepository.getAllStocks() }
    val snapshotDate = stocks.firstOrNull()?.asOfDate ?: snapshotState.snapshotDate
    val priceDate = stocks.firstOrNull()?.m04Price6m?.asOfDate ?: snapshotState.priceCutoffDate
    val rankedStocks = remember(stocks) { stocks.filter { it.rankOrder != null }.sortedBy { it.rankOrder } }
    val candidates = remember(stocks, snapshotState.revision) { FinalCandidateRepository.getFinalCandidateMap() }
    val safety = remember(stocks, snapshotState.revision) { FinancialSafetyRepository.getAll() }
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().background(scheme.background)) {
        NavyScreenHeader(onSearch = onSearch ?: onNavigateToList)
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("home_list"),
            contentPadding = PaddingValues(start = 11.dp, end = 11.dp, bottom = 20.dp),
        ) {
            item {
                PrimaryStockExplorer(stocks.size, rankedStocks.size, onNavigateToList)
                Spacer(Modifier.height(7.dp))
            }
            item {
                SnapshotCompactCard(stocks.size, rankedStocks.size, snapshotDate, priceDate, snapshotState)
                Spacer(Modifier.height(6.dp))
                NavyUniverseCounts(stocks.size, rankedStocks.size)
                Spacer(Modifier.height(7.dp))
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(17.dp).background(scheme.primary, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text("4지표 상위 순위", fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(99.dp), color = scheme.surfaceVariant, border = BorderStroke(1.dp, scheme.outlineVariant)) {
                        Text("${rankedStocks.size}종목", Modifier.padding(horizontal = 9.dp, vertical = 4.dp).testTag("ranked_stock_count"), fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text("상위 5종목 미리보기 · 재무위험 정보는 순위에 영향 없음", Modifier.padding(start = 11.dp, top = 2.dp, bottom = 6.dp), fontSize = 10.sp, lineHeight = 13.sp, color = scheme.onSurfaceVariant)
                StockTableHeading(showMetrics = true)
            }
            items(rankedStocks.take(5), key = { "${it.market}:${it.issuerId}" }) { stock ->
                StockSummaryCard(
                    stock = stock,
                    onClick = { onStockClick(stock.issuerId) },
                    financialSafety = safety[stock.issuerId],
                    stage45Content = {
                        Stage45CompactPanel(candidates[stock.issuerId], safety[stock.issuerId], ValuationBandPolicy.classify(stock.m03Per), showRatios = true)
                    },
                )
            }
            item {
                Spacer(Modifier.height(7.dp))
                NavyFullListAction("상위 순위 전체보기 (${rankedStocks.size}종목)  ›", "home_full_ranking_action", onNavigateToList)
                Spacer(Modifier.height(14.dp))
                DataCoverageCompact(stocks.size, stocks.count { it.m01RevGrowth.isAvailable }, stocks.count { it.m02OpMargin.isAvailable }, stocks.count { it.m03Per.isAvailable }, stocks.count { it.m04Price6m.isAvailable })
                Spacer(Modifier.height(8.dp))
                FinanceGuideCompact()
                Text("종목 KRX KIND · 재무 OpenDART · PER/가격 네이버증권 공개 데이터", Modifier.padding(top = 9.dp), fontSize = 9.sp, lineHeight = 12.sp, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** Shared app identity; both actions open real search or current update evidence. */
@Composable
fun NavyScreenHeader(onSearch: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val status by SnapshotAutoUpdater.state.collectAsState()
    var showUpdates by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 15.dp, end = 8.dp, top = 4.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(24.dp).height(25.dp)) {
            val barWidth = size.width / 4.5f
            listOf(.4f, .68f, 1f).forEachIndexed { index, fraction ->
                drawRoundRect(Color(0xFF178CFF), topLeft = Offset(index * barWidth * 1.5f, size.height * (1 - fraction)), size = androidx.compose.ui.geometry.Size(barWidth, size.height * fraction), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f))
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("KR4 국내주식", fontSize = 19.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
            Text("성장 · 수익성 · 가치 · 흐름", fontSize = 10.sp, lineHeight = 13.sp, color = scheme.onSurfaceVariant)
        }
        NavyHeaderActions(onSearch = onSearch, onUpdates = { showUpdates = true })
    }
    if (showUpdates) AlertDialog(
        onDismissRequest = { showUpdates = false },
        title = { Text("데이터 갱신 상태") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("데이터 출처 ${status.source.name}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("재무 ${status.snapshotDate} · 주가 ${status.priceCutoffDate}", fontSize = 12.sp)
                Text("최근 갱신 ${snapshotTimeText(status.refreshedAt)}", fontSize = 12.sp)
                Text("최근 확인 ${snapshotTimeText(status.lastAttemptAt)}", fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text(status.message, fontSize = 12.sp)
                if (status.failure != null) Text("갱신 실패 · 마지막 정상 자료를 표시합니다", color = scheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { showUpdates = false }) { Text("닫기") } },
    )
}

/** Search navigates to the real list; the bell opens the caller's current data receipt. */
@Composable
fun NavyHeaderActions(onSearch: (() -> Unit)?, onUpdates: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val status by SnapshotAutoUpdater.state.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onSearch != null) IconButton(onClick = onSearch, modifier = Modifier.size(34.dp).semantics { contentDescription = "종목 검색" }) {
            Canvas(Modifier.size(19.dp)) {
                drawCircle(scheme.onSurface, radius = size.width * .34f, center = Offset(size.width * .41f, size.height * .41f), style = Stroke(1.6.dp.toPx()))
                drawLine(scheme.onSurface, Offset(size.width * .65f, size.height * .65f), Offset(size.width * .94f, size.height * .94f), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        IconButton(onClick = onUpdates, modifier = Modifier.size(34.dp).testTag("snapshot_updates_action").semantics { contentDescription = "데이터 갱신 상태" }) {
            Canvas(Modifier.size(20.dp)) {
                val stroke = 1.4.dp.toPx()
                drawArc(scheme.onSurface, 180f, 180f, false, Offset(size.width * .2f, size.height * .1f), androidx.compose.ui.geometry.Size(size.width * .6f, size.height * .8f), style = Stroke(stroke))
                drawLine(scheme.onSurface, Offset(size.width * .2f, size.height * .49f), Offset(size.width * .13f, size.height * .76f), stroke)
                drawLine(scheme.onSurface, Offset(size.width * .8f, size.height * .49f), Offset(size.width * .87f, size.height * .76f), stroke)
                drawLine(scheme.onSurface, Offset(size.width * .13f, size.height * .76f), Offset(size.width * .87f, size.height * .76f), stroke)
                drawArc(scheme.onSurface, 0f, 180f, false, Offset(size.width * .39f, size.height * .8f), androidx.compose.ui.geometry.Size(size.width * .22f, size.height * .16f), style = Stroke(stroke))
                if (status.source == SnapshotAutoUpdater.Source.LIVE && status.failure == null) drawCircle(scheme.primary, radius = size.width * .1f, center = Offset(size.width * .92f, size.height * .08f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NavyFullListAction(label: String, tag: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 29.dp).testTag(tag), shape = RoundedCornerShape(8.dp), color = scheme.surfaceVariant, border = BorderStroke(1.dp, scheme.outline)) {
            Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun PrimaryStockExplorer(total: Int, rankedCount: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxWidth().testTag("primary_stock_explorer").clickable(onClick = onClick), shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, Color(0xFF355781)), color = Color(0xFF0C2852)) {
        Box(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF103B78), Color(0xFF0B203E))))) {
            Canvas(Modifier.align(Alignment.CenterEnd).width(125.dp).height(75.dp)) {
                for (index in 0..11) {
                    val x = size.width * (.08f + index * .077f)
                    val y = size.height * (.94f - index * .067f)
                    drawLine(scheme.primary.copy(alpha = .16f), Offset(x, y - size.height * .1f), Offset(x, y + size.height * .1f), 1.dp.toPx())
                    drawLine(scheme.primary.copy(alpha = .25f), Offset(x, y - size.height * .05f), Offset(x, y + size.height * .05f), 4.dp.toPx())
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Surface(shape = RoundedCornerShape(99.dp), color = Color(0xFF17559B), border = BorderStroke(.5.dp, Color(0xFF3A78B7))) {
                        Text("4축 스크리닝", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("4지표 전체 종목보기", Modifier.padding(top = 4.dp), fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
                    Text("${total}종목 공개 · 순위 산출 ${rankedCount}종목 · 결측 ${total - rankedCount}종목", Modifier.padding(top = 3.dp), fontSize = 10.sp, lineHeight = 14.sp, color = Color(0xFFD5E3F3))
                }
                Text("›", fontSize = 27.sp)
            }
        }
    }
}

@Composable
fun SnapshotCompactCard(total: Int, complete: Int, snapshotDate: String, priceDate: String, status: SnapshotAutoUpdater.Result) {
    val scheme = MaterialTheme.colorScheme
    val matches = status.snapshotDate == snapshotDate && status.priceCutoffDate == priceDate
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(9.dp), color = scheme.surface, border = BorderStroke(1.dp, scheme.outline)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SnapshotSourceHeading(status, matches, "snapshot_source")
                Spacer(Modifier.height(3.dp))
                SnapshotMetadata("layers", "4지표 완성 $complete/$total", "snapshot_coverage")
                SnapshotMetadata("calendar", "재무 $snapshotDate · 주가 $priceDate", "snapshot_dates")
                SnapshotMetadata("refresh", if (matches) "최근 갱신 ${snapshotTimeText(status.refreshedAt)}" else "최근 갱신 확인 중", "snapshot_last_refresh")
                if (status.lastAttemptAt.isNotBlank()) SnapshotMetadata("check", "최근 확인 ${snapshotTimeText(status.lastAttemptAt)}", "snapshot_last_attempt")
                if (matches && status.failure != null) Text("갱신 실패 · 마지막 정상 자료를 표시합니다", Modifier.testTag("snapshot_failure"), fontSize = 9.sp, lineHeight = 12.sp, color = scheme.error)
            }
            Box(Modifier.padding(horizontal = 8.dp).width(.5.dp).height(45.dp).background(scheme.outlineVariant))
            Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { if (total > 0) complete.toFloat() / total else 0f }, modifier = Modifier.fillMaxSize(), color = scheme.primary, trackColor = scheme.outlineVariant, strokeWidth = 6.dp, strokeCap = StrokeCap.Butt)
                Text(percentText(complete, total), fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun SnapshotSourceHeading(status: SnapshotAutoUpdater.Result, matches: Boolean, tag: String) {
    val scheme = MaterialTheme.colorScheme
    val sourceSentence = if (matches) "데이터 출처 ${status.source.name}" else "데이터 출처 확인 중"
    Row(
        modifier = Modifier.clearAndSetSemantics {
            this[SemanticsProperties.TestTag] = tag
            this[SemanticsProperties.Text] = listOf(AnnotatedString(sourceSentence))
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(if (matches && status.failure == null) Color(0xFF1BCF7B) else scheme.onSurfaceVariant, RoundedCornerShape(99.dp)))
        Spacer(Modifier.width(5.dp))
        Text(if (matches) "데이터 출처" else sourceSentence, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold)
        if (matches) {
            Spacer(Modifier.width(5.dp))
            Surface(shape = RoundedCornerShape(99.dp), color = Color(0xFF165EA6), contentColor = Color.White, border = BorderStroke(.5.dp, Color(0xFF367DBB))) {
                Text(status.source.name, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun SnapshotMetadata(icon: String, text: String, tag: String? = null, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SnapshotOutlineIcon(icon, Modifier.size(10.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, if (tag == null) Modifier else Modifier.testTag(tag), fontSize = 9.sp, lineHeight = 13.sp, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun SnapshotOutlineIcon(kind: String, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val stroke = 1.dp.toPx()
        val w = size.width
        val h = size.height
        when (kind) {
            "warning" -> {
                val top = Offset(w * .5f, h * .08f)
                val left = Offset(w * .08f, h * .88f)
                val right = Offset(w * .92f, h * .88f)
                drawLine(color, top, left, stroke, StrokeCap.Round)
                drawLine(color, left, right, stroke, StrokeCap.Round)
                drawLine(color, right, top, stroke, StrokeCap.Round)
                drawLine(color, Offset(w * .5f, h * .35f), Offset(w * .5f, h * .57f), stroke, StrokeCap.Round)
                drawCircle(color, radius = stroke * .55f, center = Offset(w * .5f, h * .73f))
            }
            "calendar" -> {
                drawRoundRect(color, Offset(w * .12f, h * .2f), androidx.compose.ui.geometry.Size(w * .76f, h * .7f), androidx.compose.ui.geometry.CornerRadius(w * .08f), style = Stroke(stroke))
                drawLine(color, Offset(w * .12f, h * .43f), Offset(w * .88f, h * .43f), stroke)
                drawLine(color, Offset(w * .3f, h * .05f), Offset(w * .3f, h * .3f), stroke)
                drawLine(color, Offset(w * .7f, h * .05f), Offset(w * .7f, h * .3f), stroke)
            }
            "layers" -> {
                val points = listOf(Offset(w * .1f, h * .3f), Offset(w * .5f, h * .08f), Offset(w * .9f, h * .3f), Offset(w * .5f, h * .52f), Offset(w * .1f, h * .3f))
                points.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, stroke) }
                for (y in listOf(.52f, .73f)) {
                    drawLine(color, Offset(w * .1f, h * y), Offset(w * .5f, h * (y + .22f)), stroke)
                    drawLine(color, Offset(w * .5f, h * (y + .22f)), Offset(w * .9f, h * y), stroke)
                }
            }
            else -> {
                drawCircle(color, radius = w * .4f, center = Offset(w * .5f, h * .5f), style = Stroke(stroke))
                if (kind == "check") {
                    drawLine(color, Offset(w * .28f, h * .5f), Offset(w * .44f, h * .66f), stroke)
                    drawLine(color, Offset(w * .44f, h * .66f), Offset(w * .74f, h * .34f), stroke)
                } else {
                    drawLine(color, Offset(w * .5f, h * .22f), Offset(w * .5f, h * .5f), stroke)
                    drawLine(color, Offset(w * .5f, h * .5f), Offset(w * .75f, h * .5f), stroke)
                }
            }
        }
    }
}

internal fun snapshotTimeText(value: String): String {
    if (value.isBlank()) return "기록 없음"
    return runCatching { OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'KST'")) }.getOrDefault("확인 중")
}

@Composable
internal fun NavyUniverseCounts(total: Int, ranked: Int, modifier: Modifier = Modifier, inline: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = scheme.surface, border = BorderStroke(1.dp, scheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            listOf("전체 종목" to total, "순위 산출" to ranked, "결측(미산출)" to total - ranked).forEachIndexed { index, (label, count) ->
                if (index > 0) Box(Modifier.width(.5.dp).height(if (inline) 12.dp else 24.dp).background(scheme.outlineVariant))
                if (inline) {
                    Row(Modifier.weight(1f).padding(horizontal = 5.dp, vertical = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(label, fontSize = 8.sp, lineHeight = 11.sp, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text("${String.format("%,d", count)}종목", fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Row(Modifier.weight(1f).background(if (index == 1) Color(0xFF0D2B50) else Color.Transparent).padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (index == 1) {
                            Canvas(Modifier.size(15.dp)) {
                                for (bar in 0..2) drawLine(scheme.primary, Offset(size.width * (.15f + bar * .3f), size.height * .9f), Offset(size.width * (.15f + bar * .3f), size.height * (.65f - bar * .25f)), 3.dp.toPx(), StrokeCap.Round)
                            }
                        } else SnapshotOutlineIcon(if (index == 0) "layers" else "warning", Modifier.size(15.dp))
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, fontSize = 8.sp, lineHeight = 11.sp, color = scheme.onSurfaceVariant)
                            Text("${String.format("%,d", count)}종목", fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StockTableHeading(showMetrics: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp), color = scheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("순위", Modifier.width(33.dp), fontSize = 9.sp, lineHeight = 12.sp, color = scheme.onSurfaceVariant)
            Text(if (showMetrics) "종목정보" else "종목명 (종목코드)", Modifier.weight(1f), fontSize = 9.sp, lineHeight = 12.sp, color = scheme.onSurfaceVariant)
            Text(if (showMetrics) "4지표 요약" else "시장 / 업종", Modifier.weight(if (showMetrics) 1.07f else .8f), fontSize = 9.sp, lineHeight = 12.sp, color = scheme.onSurfaceVariant)
            Text("종합점수", Modifier.width(if (showMetrics) 57.dp else 110.dp), fontSize = 9.sp, lineHeight = 12.sp, color = scheme.onSurfaceVariant)
        }
    }
}

/** A compact table row. Raw facts and the existing Stage4/5 evidence remain expandable. */
@Composable
fun StockSummaryCard(
    stock: StockSummary,
    onClick: () -> Unit,
    displayRank: Int? = stock.rankOrder,
    displayScore: Double? = stock.compositeScore,
    scoreLabel: String = "4지표 종합 상대점수",
    missingLabel: String = "실데이터 ${availableMetricCount(stock)}/4 · 순위 보류",
    financialSafety: FinancialSafetyRecord? = null,
    stage45Content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val showMetrics = LocalStockRowMetrics.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val scoreWidth = (if (showMetrics) 49 else 52).dp * fontScale
    var expanded by remember(stock.issuerId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(scheme.surface.copy(alpha = .48f)).testTag("stock_${stock.issuerId}").clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = if (showMetrics) 2.dp else 1.dp).heightIn(min = if (showMetrics) 37.dp else 27.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.widthIn(min = 29.dp), shape = RoundedCornerShape(5.dp), color = if (displayRank != null) Color(0xFF12467C) else scheme.surfaceVariant, border = BorderStroke(.7.dp, if (displayRank != null) Color(0xFF2868A6) else scheme.outlineVariant)) {
                Text(displayRank?.let { "${it}위" } ?: "—", Modifier.padding(horizontal = 2.dp, vertical = 3.dp).testTag("stock_rank_${stock.issuerId}"), fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(if (showMetrics) 1.18f else 1f)) {
                if (showMetrics) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stock.name, Modifier.weight(1f).fillMaxWidth().testTag("stock_name_${stock.issuerId}"), fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(3.dp))
                        DenseSafetyBadge(financialSafety)
                    }
                } else {
                    // Match the rendered and semantic column width even for a single-line name.
                    Text(stock.name, Modifier.fillMaxWidth().testTag("stock_name_${stock.issuerId}"), fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
                }
                Text(if (showMetrics) "${stock.issuerId} · ${stock.market}" else stock.issuerId, Modifier.testTag("stock_code_${stock.issuerId}"), fontSize = 8.sp, lineHeight = 11.sp, color = scheme.onSurfaceVariant)
                if (showMetrics) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stock.sector, Modifier.weight(1f), fontSize = 8.sp, lineHeight = 10.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(7.dp))
            if (showMetrics) {
                Column(Modifier.weight(1f).testTag("comparison_${stock.issuerId}"), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    MiniMetricRow("성장", stock.m01RevGrowth)
                    MiniMetricRow("수익성", stock.m02OpMargin)
                    MiniMetricRow("가치", stock.m03Per)
                    MiniMetricRow("흐름", stock.m04Price6m)
                }
            } else {
                Column(Modifier.weight(.8f).testTag("comparison_${stock.issuerId}")) {
                    Text(stock.market, fontSize = 8.sp, lineHeight = 11.sp, color = scheme.onSurfaceVariant)
                    Text(stock.sector, fontSize = 8.sp, lineHeight = 11.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.width(scoreWidth), horizontalAlignment = Alignment.End) {
                Text(displayScore?.let { String.format("%.1f점", it) } ?: "미산출", Modifier.testTag("stock_score_${stock.issuerId}"), fontSize = if (displayScore != null) 15.sp else 11.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold)
            }
            if (!showMetrics) {
                Spacer(Modifier.width(5.dp))
                DenseSafetyBadge(financialSafety)
            }
            Box(Modifier.padding(start = 4.dp).width(12.dp).heightIn(min = if (showMetrics) 30.dp else 27.dp).clickable { expanded = !expanded }.testTag("stock_evidence_${stock.issuerId}"), contentAlignment = Alignment.Center) {
                Text(if (expanded) "⌃" else "›", fontSize = 17.sp, color = scheme.onSurfaceVariant)
            }
        }
        if (displayScore == null) Text(missingLabel, Modifier.padding(start = 43.dp, end = 8.dp, bottom = 5.dp), fontSize = 9.sp, lineHeight = 13.sp, color = scheme.onSurfaceVariant)
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("한눈 비교", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("시장 내 상대위치", fontSize = 9.sp, lineHeight = 12.sp, color = scheme.onSurfaceVariant)
                    StatusBadge(stock.status)
                }
                Text("${stock.issuerId} · ${stock.market} · ${stock.sector}", Modifier.padding(top = 4.dp), fontSize = 10.sp, lineHeight = 14.sp, color = scheme.onSurfaceVariant)
                Text(if (displayScore != null) scoreLabel else missingLabel, Modifier.padding(top = 4.dp), fontSize = 10.sp, color = scheme.onSurfaceVariant)
                listOf(stock.m01RevGrowth, stock.m02OpMargin, stock.m03Per, stock.m04Price6m).forEach { metric ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                        Text(metric.nameKo, Modifier.weight(1f), fontSize = 10.sp)
                        Column(Modifier.weight(1.8f), horizontalAlignment = Alignment.End) {
                            Text(metricValueText(metric), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (metric.isAvailable) marketPosition(metric.percentileScore) else metric.reason ?: if (stock.isLossMaking && metric.id == "M03") "적자 · PER 미적용" else "자료 확인 필요", fontSize = 9.sp, lineHeight = 13.sp, color = scheme.onSurfaceVariant)
                        }
                    }
                }
                if (stage45Content != null) {
                    Spacer(Modifier.height(8.dp))
                    stage45Content()
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 6.dp), thickness = .5.dp, color = scheme.outlineVariant)
    }
}

@Composable
private fun DenseSafetyBadge(safety: FinancialSafetyRecord?) {
    FinancialSafetyBadge(safety, compact = true)
}

@Composable
private fun MiniMetricRow(label: String, metric: MetricValue) {
    val scheme = MaterialTheme.colorScheme
    val labelWidth = 24.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(labelWidth), fontSize = 7.sp, lineHeight = 9.sp, color = scheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(99.dp)).background(scheme.outlineVariant)) {
            val fraction = metric.percentileScore?.takeIf { metric.isAvailable }?.let { (it / 100).coerceIn(0.0, 1.0).toFloat() } ?: 0f
            if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Brush.horizontalGradient(listOf(scheme.primary, Color(0xFF67B9FF)))))
        }
        Text(metricValueText(metric), Modifier.width(36.dp), fontSize = 7.sp, lineHeight = 9.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End, color = scheme.onSurface)
    }
}

private fun metricValueText(metric: MetricValue): String = when {
    !metric.isAvailable || metric.rawValue == null -> "N/A"
    metric.id == "M03" -> String.format("%.1f배", metric.rawValue)
    else -> String.format("%.1f%%", metric.rawValue)
}

private fun marketPosition(score: Double?): String = when {
    score == null -> "상대점수 없음"
    score >= 90 -> "시장 상위 10%"
    score >= 75 -> "시장 상위 25%"
    score >= 50 -> "시장 중상위"
    score >= 25 -> "시장 중하위"
    else -> "시장 하위"
}

private fun availableMetricCount(stock: StockSummary): Int = listOf(stock.m01RevGrowth, stock.m02OpMargin, stock.m03Per, stock.m04Price6m).count { it.isAvailable }
private fun percentText(value: Int, total: Int): String = if (total <= 0) "0%" else String.format("%.1f%%", value * 100.0 / total)

@Composable
private fun DataCoverageCompact(total: Int, m01: Int, m02: Int, m03: Int, m04: Int) {
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = scheme.surface, border = BorderStroke(1.dp, scheme.outlineVariant)) {
        Column(Modifier.padding(11.dp)) {
            Text("데이터 커버리지", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            listOf("매출 증가율" to m01, "영업이익률" to m02, "실적 PER" to m03, "6개월 상승률" to m04).forEach { (label, count) ->
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, fontSize = 10.sp, color = scheme.onSurfaceVariant)
                    Text("${count}개 · ${percentText(count, total)}", fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun FinanceGuideCompact() {
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = scheme.surface, border = BorderStroke(1.dp, scheme.outlineVariant)) {
        Column(Modifier.padding(11.dp)) {
            Text("4지표 해석 프레임", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("성장=매출 · 수익성=영업이익률 · 가치=실적 PER · 흐름=6개월 주가", Modifier.padding(top = 5.dp), fontSize = 10.sp, lineHeight = 13.sp, color = scheme.onSurfaceVariant)
        }
    }
}

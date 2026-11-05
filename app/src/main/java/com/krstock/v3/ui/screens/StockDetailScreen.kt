package com.krstock.v3.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.analysis.IntegratedInterpretationEngine
import com.krstock.v3.data.analysis.QuarterlyTrendAnalyzer
import com.krstock.v3.data.candidate.FinalCandidateRepository
import com.krstock.v3.data.evidence.ContextEvidenceRepository
import com.krstock.v3.data.history.QuarterlyHistoryRepository
import com.krstock.v3.data.price.PriceHistoryRepository
import com.krstock.v3.data.model.*
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.stage.FinancialSafetyRepository
import com.krstock.v3.data.stage.ValuationBandPolicy
import com.krstock.v3.data.update.SnapshotAutoUpdater
import com.krstock.v3.ui.components.FinalCandidateDetailCard
import com.krstock.v3.ui.components.FinancialSafetyCard
import com.krstock.v3.ui.components.PriceChartPanel
import com.krstock.v3.ui.components.StatusBadge
import com.krstock.v3.ui.components.ValuationCard
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    issuerId: String,
    onBack: () -> Unit,
    onSearch: (() -> Unit)? = null,
    onTabChanged: (Int) -> Unit = {},
) {
    val snapshotState by SnapshotAutoUpdater.state.collectAsState()
    val stockDetail = remember(issuerId, snapshotState.revision) { StockRepository.getStockDetail(issuerId) }
    val scheme = MaterialTheme.colorScheme
    var showUpdates by remember { mutableStateOf(false) }
    if (showUpdates) {
        val allStocks = remember(snapshotState.revision) { StockRepository.getAllStocks() }
        val snapshotDate = allStocks.firstOrNull()?.asOfDate ?: snapshotState.snapshotDate
        val priceDate = allStocks.firstOrNull()?.m04Price6m?.asOfDate ?: snapshotState.priceCutoffDate
        AlertDialog(
            modifier = Modifier.testTag("detail_snapshot_updates"),
            onDismissRequest = { showUpdates = false },
            title = { Text("데이터 갱신 상태") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SnapshotCompactCard(
                        total = allStocks.size,
                        complete = allStocks.count { it.isCompositeComplete },
                        snapshotDate = snapshotDate,
                        priceDate = priceDate,
                        status = snapshotState,
                    )
                    Text(snapshotState.message, Modifier.padding(top = 10.dp), fontSize = 12.sp, lineHeight = 18.sp)
                }
            },
            confirmButton = { TextButton(onClick = { showUpdates = false }) { Text("닫기") } },
        )
    }

    if (stockDetail == null) {
        Scaffold(
            containerColor = scheme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
                    title = { Text("종목을 찾을 수 없음", fontWeight = FontWeight.Bold) },
                    navigationIcon = { TextButton(onClick = onBack) { Text("‹ 뒤로") } },
                    actions = { NavyHeaderActions(onSearch = onSearch, onUpdates = { showUpdates = true }) },
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("요청한 종목코드 '$issuerId'가 KOSPI·KOSDAQ 마스터에 없습니다.", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("없는 종목을 다른 회사로 대체하지 않습니다.", fontSize = 13.sp, color = scheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onBack) { Text("목록으로 돌아가기") }
            }
        }
        return
    }

    val summary = stockDetail.summary
    val report = stockDetail.report
    val finalCandidate = remember(issuerId, snapshotState.revision) { FinalCandidateRepository.getFinalCandidate(issuerId) }
    val financialSafety = remember(issuerId, snapshotState.revision) { FinancialSafetyRepository.get(issuerId) }
    val valuationBand = remember(summary) { ValuationBandPolicy.classify(summary.m03Per) }
    val context = LocalContext.current
    var evidence by remember(issuerId) { mutableStateOf(EvidenceBundle(issuerId = issuerId)) }
    var quarterlyHistory by remember(issuerId) { mutableStateOf(QuarterlyHistory.empty(issuerId)) }
    LaunchedEffect(issuerId) {
        evidence = ContextEvidenceRepository.load(issuerId)
    }
    LaunchedEffect(issuerId) {
        quarterlyHistory = QuarterlyHistoryRepository.load(context, issuerId)
    }
    val integrated = remember(summary, evidence, quarterlyHistory) {
        QuarterlyTrendAnalyzer.enrich(
            IntegratedInterpretationEngine.analyze(summary, evidence),
            quarterlyHistory
        )
    }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 5 })
    LaunchedEffect(pagerState.currentPage) { onTabChanged(pagerState.currentPage) }
    val scope = rememberCoroutineScope()
    val tabs = listOf("요약", "4지표", "종합해석", "체크·출처", "차트")
    val tabFontSize = if (LocalConfiguration.current.screenWidthDp < 340 || LocalDensity.current.fontScale > 1.15f) 9.sp else 11.sp
    var priceChart by remember(issuerId, snapshotState.priceCutoffDate) {
        mutableStateOf<PriceHistoryRepository.Result?>(null)
    }
    var chartLoading by remember(issuerId) { mutableStateOf(false) }
    LaunchedEffect(issuerId, snapshotState.priceCutoffDate) {
        chartLoading = true
        priceChart = runCatching {
            PriceHistoryRepository.load(context, issuerId, StockRepository.priceCutoffDate())
        }.getOrNull()
        chartLoading = false
    }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            Surface(color = scheme.background) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(34.dp).semantics { contentDescription = "뒤로" }) {
                        Text("‹", fontSize = 30.sp, lineHeight = 32.sp)
                    }
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            "${summary.name} (${summary.issuerId})",
                            modifier = Modifier.fillMaxWidth().testTag("detail_title"),
                            fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp,
                        )
                        Text(
                            "${summary.market} · ${summary.sector}",
                            fontSize = 9.sp, lineHeight = 12.sp, color = scheme.onSurfaceVariant,
                        )
                    }
                    NavyHeaderActions(onSearch = onSearch, onUpdates = { showUpdates = true })
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (pagerState.currentPage != 4) DetailQuotePanel(result = priceChart, loading = chartLoading)
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).testTag("detail_tabs"),
                containerColor = scheme.background,
                contentColor = scheme.onSurface,
                indicator = { tabPositions ->
                    if (pagerState.currentPage == 4) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[4]),
                            height = 3.dp,
                            color = scheme.primary,
                        )
                    }
                },
                divider = { HorizontalDivider(color = scheme.outline) },
            ) {
                tabs.forEachIndexed { index, title ->
                    val selected = pagerState.currentPage == index
                    val showPill = selected && pagerState.currentPage != 4
                    Tab(
                        selected = selected,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        modifier = Modifier.height(36.dp),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                            shape = RoundedCornerShape(7.dp),
                            color = if (showPill) scheme.primary.copy(alpha = .24f) else scheme.background,
                            border = if (showPill) BorderStroke(1.dp, scheme.primary.copy(alpha = .34f)) else null,
                        ) {
                            Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    title, fontSize = tabFontSize, lineHeight = 14.sp,
                                    maxLines = 1, softWrap = false,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().testTag("detail_pager")
            ) { page ->
                when (page) {
                    0 -> DetailSummaryPage(
                        summary = summary,
                        analysis = integrated,
                        finalCandidate = finalCandidate,
                        financialSafety = financialSafety,
                        valuationBand = valuationBand,
                    )
                    1 -> DetailMetricsPage(summary = summary, financialSafety = financialSafety)
                    2 -> DetailAnalysisPage(analysis = integrated, evidence = evidence, history = quarterlyHistory)
                    3 -> DetailCheckSourcePage(report = report, evidence = evidence)
                    else -> PageColumn("detail_chart_page") {
                        PriceChartPanel(result = priceChart, loading = chartLoading)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailQuotePanel(result: PriceHistoryRepository.Result?, loading: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    val showSparkline = LocalConfiguration.current.screenWidthDp >= 360 && fontScale <= 1.15f
    val stackQuote = LocalConfiguration.current.screenWidthDp < 360 && fontScale > 1.15f
    val bars = result?.bars.orEmpty().sortedBy { it.date }
    val latest = bars.lastOrNull()
    val previous = bars.getOrNull(bars.lastIndex - 1)
    val change = if (latest != null && previous != null) latest.close - previous.close else null
    val changeColor = if (change != null && change > 0) scheme.error else scheme.primary
    val closeText = latest?.let { String.format(Locale.KOREA, "%,.0f원", it.close) }
        ?: if (loading) "종가 확인 중" else "종가 미확보"
    val changeText = if (change != null && previous != null) {
        String.format(Locale.KOREA, "%s %+,.0f원 (%+.2f%%)",
            if (change > 0) "▲" else if (change < 0) "▼" else "—",
            change, change / previous.close * 100.0)
    } else null
    val sourceLabel = when (result?.source) {
        PriceHistoryRepository.Source.CACHE -> "네이버증권 · 저장자료"
        PriceHistoryRepository.Source.LIVE -> "네이버증권 · 완료 일봉"
        else -> "네이버증권 · 완료 일봉 기준"
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("detail_quote_panel"),
        shape = RoundedCornerShape(9.dp),
        color = scheme.primaryContainer,
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.32f)),
    ) {
        Box(
            modifier = Modifier.background(
                Brush.horizontalGradient(listOf(scheme.primary.copy(alpha = 0.14f), scheme.primaryContainer))
            ),
        ) {
            if (showSparkline && bars.size > 1) {
                val samples = bars.takeLast(24)
                val lowest = samples.minOf { it.close }
                val range = samples.maxOf { it.close } - lowest
                Canvas(Modifier.align(Alignment.CenterEnd).offset(x = (-85).dp).width(110.dp).height(46.dp)) {
                    val path = Path()
                    samples.forEachIndexed { index, bar ->
                        val x = index.toFloat() / samples.lastIndex * size.width
                        val y = if (range > 0.0) size.height * (1f - ((bar.close - lowest) / range).toFloat()) else size.height / 2f
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color = scheme.primary.copy(alpha = 0.22f), style = Stroke(width = 2.dp.toPx()))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (stackQuote) {
                        Text(
                            closeText,
                            modifier = Modifier.testTag("detail_quote_close"),
                            fontSize = if (latest == null) 16.sp else 20.sp, lineHeight = 24.sp,
                            fontWeight = FontWeight.Bold, color = scheme.onSurface,
                        )
                        if (changeText != null) {
                            Text(
                                changeText,
                                fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold, color = changeColor,
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(
                                closeText,
                                modifier = Modifier.testTag("detail_quote_close"),
                                fontSize = if (latest == null) 16.sp else 20.sp, lineHeight = 24.sp,
                                fontWeight = FontWeight.Bold, color = scheme.onSurface,
                            )
                            if (changeText != null) {
                                Text(
                                    changeText, modifier = Modifier.weight(1f),
                                    fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold, color = changeColor,
                                )
                            }
                        }
                    }
                    Text(
                        latest?.let { "${it.date} · $sourceLabel" } ?: sourceLabel,
                        fontSize = 7.sp, lineHeight = 9.sp, color = scheme.onSurfaceVariant,
                    )
                }
                VerticalDivider(Modifier.height(45.dp), color = scheme.onSurfaceVariant.copy(alpha = .32f))
                Column(
                    Modifier.widthIn(min = 76.dp * fontScale.coerceAtLeast(1f)).width(IntrinsicSize.Max),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    QuoteFact("시가", latest?.open?.let { String.format(Locale.KOREA, "%,.0f", it) })
                    QuoteFact("고가", latest?.high?.let { String.format(Locale.KOREA, "%,.0f", it) })
                    QuoteFact("저가", latest?.low?.let { String.format(Locale.KOREA, "%,.0f", it) })
                    QuoteFact("거래량", latest?.volume?.let { String.format(Locale.KOREA, "%,d주", it) })
                }
            }
        }
    }
}

@Composable
private fun QuoteFact(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 8.sp, lineHeight = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value ?: "—", modifier = Modifier.weight(1f).testTag("detail_quote_fact_value_$label"),
            fontSize = 8.sp, lineHeight = 10.sp, color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End, maxLines = 1, softWrap = false,
        )
    }
}

@Composable
private fun DetailSummaryPage(
    summary: StockSummary,
    analysis: IntegratedAnalysis,
    finalCandidate: FinalCandidateRecord?,
    financialSafety: FinancialSafetyRecord?,
    valuationBand: ValuationBand?,
) {
    val scheme = MaterialTheme.colorScheme
    PageColumn("detail_summary_page") {
        CompositeScorePanel(summary, tagSummaryValues = true)
        CompactMetricsOverview(summary)

        if (finalCandidate != null) {
            FinalCandidateDetailCard(finalCandidate)
        }

        FinancialSafetyCard(
            safety = financialSafety,
            snapshotDate = summary.asOfDate,
        )

        ValuationCard(
            metric = summary.m03Per,
            band = valuationBand,
        )

        FinanceSectionCard(title = "핵심 판독") {
            Text(analysis.regimeTitle, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
            Spacer(modifier = Modifier.height(7.dp))
            Text(analysis.thesis, fontSize = 12.sp, lineHeight = 20.sp)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant),
            border = BorderStroke(1.dp, scheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                IdentityRow("시장", summary.market)
                IdentityRow("업종", summary.sector)
                IdentityRow("상장일", summary.listingDate.ifBlank { "확인 필요" })
                IdentityRow("재무 기준", summary.asOfDate)
                IdentityRow("주가 기준", summary.m04Price6m.asOfDate)
            }
        }

        SwipeHint("← 밀어서 4대 정량지표 보기")
    }
}

@Composable
private fun CompositeScorePanel(summary: StockSummary, tagSummaryValues: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val availableCount = listOf(summary.m01RevGrowth, summary.m02OpMargin, summary.m03Per, summary.m04Price6m)
        .count { it.isAvailable }
    Card(
        modifier = Modifier.fillMaxWidth().testTag(if (tagSummaryValues) "detail_summary_composite" else "detail_metrics_composite"),
        shape = RoundedCornerShape(11.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outline),
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(20.dp).background(scheme.primary, RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("종합점수", fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                    Text("4지표 종합 상대평가 · 백분위와 구분", fontSize = 8.sp, lineHeight = 10.sp, color = scheme.onSurfaceVariant)
                }
                Text(
                    summary.compositeScore?.let { String.format("%.1f점", it) } ?: "미산출",
                    modifier = if (tagSummaryValues) Modifier.testTag("detail_summary_score") else Modifier,
                    fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = scheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                ScoreFact("전체 종목", String.format(Locale.KOREA, "%,d종목", StockRepository.registeredCount()), Modifier.weight(1f))
                VerticalDivider(Modifier.height(26.dp), color = scheme.outlineVariant)
                ScoreFact(
                    "기본 4지표 순위", summary.rankOrder?.let { "${it}위" } ?: "보류", Modifier.weight(1f),
                    if (tagSummaryValues) "detail_summary_rank" else null,
                )
                VerticalDivider(Modifier.height(26.dp), color = scheme.outlineVariant)
                ScoreFact("실데이터", "$availableCount/4", Modifier.weight(1f))
            }
            if (tagSummaryValues) {
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "재무 ${summary.asOfDate} · 주가 ${summary.m04Price6m.asOfDate}",
                        modifier = Modifier.weight(1f).testTag("detail_snapshot_dates"),
                        fontSize = 8.sp, color = scheme.onSurfaceVariant,
                    )
                    StatusBadge(status = summary.status)
                }
                Text(
                    if (summary.isCompositeComplete) "4지표 완성 · 순위 산출 가능" else "결측값은 추정하지 않고 보류",
                    fontSize = 8.sp, color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScoreFact(label: String, value: String, modifier: Modifier = Modifier, tag: String? = null) {
    Column(modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 8.sp, lineHeight = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, modifier = if (tag != null) Modifier.testTag(tag) else Modifier, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CompactMetricIcon(index: Int) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(6.dp), color = scheme.primary.copy(alpha = .10f)) {
        Canvas(Modifier.size(25.dp).padding(5.dp)) {
            val color = scheme.primary
            val stroke = 1.1.dp.toPx()
            val w = size.width
            val h = size.height
            when (index) {
                0 -> {
                    for (bar in 0..2) {
                        val x = w * (.16f + .30f * bar)
                        drawLine(color, Offset(x, h * (.65f - .23f * bar)), Offset(x, h * .91f), stroke)
                        drawLine(color, Offset(x + w * .13f, h * (.65f - .23f * bar)), Offset(x + w * .13f, h * .91f), stroke)
                    }
                }
                1 -> {
                    drawOval(color, topLeft = Offset(w * .1f, h * .05f), size = androidx.compose.ui.geometry.Size(w * .8f, h * .3f), style = Stroke(stroke))
                    drawLine(color, Offset(w * .1f, h * .2f), Offset(w * .1f, h * .8f), stroke)
                    drawLine(color, Offset(w * .9f, h * .2f), Offset(w * .9f, h * .8f), stroke)
                    drawArc(color, 0f, 180f, false, Offset(w * .1f, h * .37f), androidx.compose.ui.geometry.Size(w * .8f, h * .3f), style = Stroke(stroke))
                    drawArc(color, 0f, 180f, false, Offset(w * .1f, h * .65f), androidx.compose.ui.geometry.Size(w * .8f, h * .3f), style = Stroke(stroke))
                }
                2 -> {
                    val path = Path().apply {
                        moveTo(w * .12f, h * .57f); lineTo(w * .56f, h * .1f)
                        lineTo(w * .9f, h * .1f); lineTo(w * .9f, h * .44f)
                        lineTo(w * .45f, h * .91f); close()
                    }
                    drawPath(path, color, style = Stroke(stroke))
                    drawCircle(color, radius = w * .05f, center = Offset(w * .72f, h * .28f))
                }
                else -> {
                    drawLine(color, Offset(w * .12f, h * .3f), Offset(w * .88f, h * .3f), stroke)
                    drawLine(color, Offset(w * .68f, h * .12f), Offset(w * .88f, h * .3f), stroke)
                    drawLine(color, Offset(w * .68f, h * .48f), Offset(w * .88f, h * .3f), stroke)
                    drawLine(color, Offset(w * .88f, h * .72f), Offset(w * .12f, h * .72f), stroke)
                    drawLine(color, Offset(w * .32f, h * .54f), Offset(w * .12f, h * .72f), stroke)
                    drawLine(color, Offset(w * .32f, h * .9f), Offset(w * .12f, h * .72f), stroke)
                }
            }
        }
    }
}

@Composable
private fun CompactMetricsOverview(summary: StockSummary, tagMetricValues: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(20.dp).background(scheme.primary, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(8.dp))
            Text(
                "4지표 상세",
                modifier = if (tagMetricValues) Modifier.weight(1f).testTag("detail_metrics_header") else Modifier.weight(1f),
                fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold,
            )
            Surface(
                shape = RoundedCornerShape(999.dp), color = scheme.surfaceVariant,
                border = BorderStroke(1.dp, scheme.outlineVariant),
            ) {
                Text(
                    "재무 ${summary.asOfDate.ifBlank { "미확보" }}",
                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    fontSize = 8.sp, lineHeight = 10.sp, color = scheme.onSurfaceVariant,
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp),
            color = scheme.surface, border = BorderStroke(1.dp, scheme.outline),
        ) {
            Column(Modifier.padding(horizontal = 8.dp)) {
                val metrics = listOf(summary.m01RevGrowth, summary.m02OpMargin, summary.m03Per, summary.m04Price6m)
                metrics.forEachIndexed { index, metric ->
                    if (index > 0) HorizontalDivider(color = scheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CompactMetricIcon(index)

                        Column(Modifier.weight(0.9f)) {
                            Text(listOf("성장", "수익성", "가치", "흐름")[index], fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
                            Text(listOf("매출 증가율", "영업이익률", "실적 PER", "6개월 수익률")[index], fontSize = 8.sp, lineHeight = 10.sp, color = scheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                metric.rawValue?.takeIf { metric.isAvailable }?.let { formatMetricValue(metric.id, it) } ?: "N/A",
                                modifier = if (tagMetricValues) Modifier.testTag("detail_${metric.id.lowercase()}_value") else Modifier,
                                fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
                            )
                            if (metric.percentileScore != null) {
                                RelativePositionBar(metric.percentileScore)
                            } else {
                                Surface(
                                    Modifier.fillMaxWidth().height(5.dp),
                                    shape = RoundedCornerShape(999.dp), color = scheme.outlineVariant,
                                ) {}
                            }
                        }
                        Text(
                            metric.percentileScore?.let { String.format("%.1f점", it) } ?: "보류",
                            modifier = Modifier.widthIn(min = 44.dp),
                            fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailMetricsPage(summary: StockSummary, financialSafety: FinancialSafetyRecord?) {
    val scheme = MaterialTheme.colorScheme
    PageColumn("detail_metrics_page") {
        CompositeScorePanel(summary)
        CompactMetricsOverview(summary, tagMetricValues = true)
        FinancialSafetyCard(financialSafety, summary.asOfDate)
        FinancialSectorNote()
        Text(
            "지표별 근거와 업종 비교",
            fontSize = 15.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            "값 · 업종 피어 중앙값 · 전체시장 상대위치 · 산출근거를 한 카드에서 확인합니다.",
            fontSize = 10.sp, color = scheme.onSurfaceVariant,
        )
        MetricCard(summary.m01RevGrowth)
        MetricCard(summary.m02OpMargin)
        MetricCard(summary.m03Per, lossMaking = summary.isLossMaking)
        MetricCard(summary.m04Price6m)
        SwipeHint("← 밀어서 4지표 종합해석 보기")
    }
}

@Composable
private fun FinancialSectorNote() {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("detail_financial_sector_note")) {
        HorizontalDivider(color = scheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().background(scheme.surface.copy(alpha = .4f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Canvas(Modifier.padding(top = 1.dp).size(12.dp)) {
                val color = scheme.onSurfaceVariant
                val stroke = 1.dp.toPx()
                drawCircle(color, radius = size.width * .43f, style = Stroke(stroke))
                drawCircle(color, radius = stroke * .7f, center = Offset(size.width * .5f, size.height * .3f))
                drawLine(color, Offset(size.width * .5f, size.height * .46f), Offset(size.width * .5f, size.height * .72f), stroke)
            }
            Text(
                "주의 · 은행·보험·증권 등 금융업은 일반기업의 부채·유동비율과 직접 비교하지 않아 비교에서 제외합니다.",
                Modifier.weight(1f), fontSize = 7.5.sp, lineHeight = 11.sp, color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailAnalysisPage(analysis: IntegratedAnalysis, evidence: EvidenceBundle, history: QuarterlyHistory) {
    val scheme = MaterialTheme.colorScheme
    PageColumn("detail_analysis_page") {
        Text(
            "4지표 종합해석",
            modifier = Modifier.testTag("integrated_analysis"),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Text(
            "숫자를 다시 읽지 않고, 네 지표가 함께 만드는 사업의 의미와 최근 근거를 해석합니다.",
            fontSize = 11.sp,
            color = scheme.onSurfaceVariant,
            lineHeight = 17.sp
        )

        AnalysisCard("CORE READ", analysis.regimeTitle) {
            Text(analysis.thesis, fontSize = 12.sp, lineHeight = 20.sp)
        }

        QuarterlyHistoryCard(analysis = analysis, history = history)

        AnalysisCard("INDUSTRY MAP", "이 산업에서 숫자를 읽는 법") {
            Text(analysis.industryContext, fontSize = 12.sp, lineHeight = 20.sp)
        }

        AnalysisCard("HIDDEN MEANING", "네 지표 조합의 숨은 뜻") {
            Text(analysis.combinationMeaning, fontSize = 12.sp, lineHeight = 20.sp)
        }

        Card(
            modifier = Modifier.fillMaxWidth().testTag("evidence_panel"),
            shape = RoundedCornerShape(15.dp),
            colors = CardDefaults.cardColors(containerColor = scheme.surface),
            border = BorderStroke(1.dp, scheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(15.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("NEWS & DISCLOSURE CHECK", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
                        Text("왜 이런 숫자가 나왔는지 최근 근거 점검", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    if (!evidence.loaded) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (evidence.error == null) scheme.secondaryContainer else scheme.errorContainer
                        ) {
                            Text(
                                if (evidence.error == null) "확인 완료" else "부분 확인",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (evidence.error == null) scheme.onSecondaryContainer else scheme.onErrorContainer
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(analysis.causeInvestigation, fontSize = 12.sp, lineHeight = 20.sp)

                if (analysis.evidenceHighlights.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = scheme.outlineVariant)
                    Spacer(Modifier.height(9.dp))
                    Text("해석에 실제로 사용한 최근 단서", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = scheme.primary)
                    Spacer(Modifier.height(6.dp))
                    analysis.evidenceHighlights.forEach { item ->
                        EvidenceRow(item)
                    }
                }
            }
        }

        AnalysisCard("OUTCOME", "이 조합이 이어질 때 생길 일") {
            Text(analysis.consequence, fontSize = 12.sp, lineHeight = 20.sp)
        }

        AnalysisCard("FALSIFIERS", "이 해석이 틀렸다고 볼 조건") {
            analysis.falsifiers.forEach { item ->
                Text("• $item", fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = scheme.surfaceVariant,
            contentColor = scheme.onSurfaceVariant
        ) {
            Text(
                analysis.confidenceNote,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                fontSize = 10.sp,
                lineHeight = 16.sp
            )
        }
        SwipeHint("← 밀어서 체크포인트·출처 보기")
    }
}

@Composable
private fun QuarterlyHistoryCard(analysis: IntegratedAnalysis, history: QuarterlyHistory) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().testTag("quarterly_history_panel"),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("4–8 QUARTER TREND", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
                    Text("실제 분기 숫자가 어떻게 변했는지", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = scheme.secondaryContainer,
                    contentColor = scheme.onSecondaryContainer
                ) {
                    Text(
                        analysis.quarterlyCoverage.ifBlank { "확인 중" },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            Text(analysis.quarterlyTrend, fontSize = 12.sp, lineHeight = 20.sp)

            if (history.points.isNotEmpty()) {
                Spacer(Modifier.height(11.dp))
                HorizontalDivider(color = scheme.outlineVariant)
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("분기", modifier = Modifier.weight(0.72f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("매출", modifier = Modifier.weight(1.25f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("YoY", modifier = Modifier.weight(0.85f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("영업률", modifier = Modifier.weight(0.85f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                history.points.takeLast(8).asReversed().forEach { point ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(point.period, modifier = Modifier.weight(0.72f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            QuarterlyTrendAnalyzer.formatAmount(point.revenue),
                            modifier = Modifier.weight(1.25f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            QuarterlyTrendAnalyzer.formatPct(point.revenueYoY),
                            modifier = Modifier.weight(0.85f),
                            fontSize = 10.sp
                        )
                        Text(
                            QuarterlyTrendAnalyzer.formatPct(point.operatingMargin),
                            modifier = Modifier.weight(0.85f),
                            fontSize = 10.sp
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    listOf(history.source, history.generatedAt).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 8.sp,
                    color = scheme.onSurfaceVariant
                )
            } else if (history.loaded) {
                Spacer(Modifier.height(8.dp))
                Text("해당 종목은 아직 검증 가능한 4분기 이상 시계열이 없습니다.", fontSize = 10.sp, color = scheme.onSurfaceVariant)
            } else {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun EvidenceRow(item: ContextEvidence) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (item.kind) {
                    EvidenceKind.DISCLOSURE -> "공시"
                    EvidenceKind.IR -> "IR"
                    EvidenceKind.OFFICIAL -> "공식자료"
                    EvidenceKind.NEWS -> "뉴스"
                },
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = scheme.primary
            )
            Spacer(Modifier.width(7.dp))
            Text(
                listOf(item.publishedAt, item.source).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 9.sp,
                color = scheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(item.title, fontSize = 11.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DetailCheckSourcePage(report: CompanyReport, evidence: EvidenceBundle) {
    val scheme = MaterialTheme.colorScheme
    PageColumn("detail_source_page") {
        Text("조사 체크포인트", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        AnalysisCard("RESEARCH CHECK", "정량 밖에서 반드시 확인할 것") {
            BulletSection("위험·결측", report.riskFactors)
            InfoSection("반대 근거", report.counterArguments)
            BulletSection("다음 확인 조건", report.nextVerificationConditions)
        }

        Card(
            modifier = Modifier.fillMaxWidth().testTag("detail_source_card"),
            colors = CardDefaults.cardColors(
                containerColor = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer
            ),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.20f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("데이터 한계와 출처", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    report.dataLimitations,
                    fontSize = 11.sp,
                    lineHeight = 18.sp,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.82f)
                )
                Spacer(modifier = Modifier.height(11.dp))
                HorizontalDivider(color = scheme.onPrimaryContainer.copy(alpha = 0.12f))
                Spacer(modifier = Modifier.height(7.dp))
                SourceRow("종목·업종", "KRX KIND")
                SourceRow("재무", "금융감독원 OpenDART")
                SourceRow("PER·가격", "네이버증권 공개 데이터")
                SourceRow("뉴스·공시", if (evidence.loaded) "네이버증권 공개 목록 · 실시간 확인" else "불러오는 중")
                SourceRow("주가 마감", StockRepository.priceCutoffDate())
                if (!evidence.error.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "최근 근거 연결 상태: ${evidence.error}",
                        fontSize = 9.sp,
                        lineHeight = 14.sp,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.72f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PageColumn(tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(tag)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun SwipeHint(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun IdentityRow(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, color = scheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SourceRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 10.sp, color = LocalContentColor.current.copy(alpha = 0.68f))
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MetricCard(metric: MetricValue, lossMaking: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val available = metric.isAvailable && metric.rawValue != null
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = scheme.surface)
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(metric.nameKo, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(metric.description, fontSize = 10.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        when {
                            !available -> "N/A"
                            metric.id == "M03" -> String.format("%.2f배", metric.rawValue)
                            else -> String.format("%.1f%%", metric.rawValue)
                        },
                        modifier = Modifier.testTag("detail_${metric.id.lowercase()}_expanded_value"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp,
                        color = if (available) scheme.onSurface else scheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (metric.percentileScore != null) scheme.primaryContainer else scheme.surfaceVariant,
                        contentColor = if (metric.percentileScore != null) scheme.onPrimaryContainer else scheme.onSurfaceVariant
                    ) {
                        Text(
                            metric.percentileScore?.let { "시장 상대 ${String.format("%.1f", it)}점" }
                                ?: if (lossMaking && metric.id == "M03") "적자 · PER 미적용" else "점수 보류",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (metric.percentileScore != null) {
                Spacer(modifier = Modifier.height(10.dp))
                RelativePositionBar(metric.percentileScore)
                Spacer(modifier = Modifier.height(5.dp))
                Text(relativeText(metric.percentileScore), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = scheme.primary)
            }

            Spacer(modifier = Modifier.height(10.dp))
            PeerMedianPanel(metric)

            Spacer(modifier = Modifier.height(10.dp))
            if (available) {
                Text(metric.interpretation, fontSize = 12.sp, lineHeight = 18.sp)
            } else {
                Text(metric.reason ?: "검증 가능한 값 없음", fontSize = 11.sp, color = scheme.error, lineHeight = 17.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = scheme.surfaceVariant,
                contentColor = scheme.onSurfaceVariant
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    if (metric.basis.isNotBlank()) MetaLine("계산", metric.basis)
                    if (metric.source.isNotBlank()) MetaLine("출처", metric.source)
                    if (metric.asOfDate.isNotBlank()) MetaLine("기준", metric.asOfDate)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("주의 · ${metric.caution}", fontSize = 9.sp, lineHeight = 15.sp, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PeerMedianPanel(metric: MetricValue) {
    val scheme = MaterialTheme.colorScheme
    val peer = metric.peerComparison
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("peer_benchmark_${metric.id}"),
        shape = RoundedCornerShape(11.dp),
        color = scheme.secondaryContainer.copy(alpha = 0.55f),
        contentColor = scheme.onSecondaryContainer,
        border = BorderStroke(1.dp, scheme.secondary.copy(alpha = 0.18f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Text("업종 피어 중앙값", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = scheme.secondary)
            Spacer(Modifier.height(3.dp))

            if (peer == null) {
                Text("피어 엔진 정보 없음", fontSize = 10.sp, color = LocalContentColor.current.copy(alpha = 0.72f))
                return@Column
            }

            val basisLabel = when (peer.basis) {
                PeerGroupBasis.KRX_EXACT_SECTOR -> "KRX 세부업종"
                PeerGroupBasis.STANDARD_SECTOR_FAMILY -> "표준 피어그룹"
                PeerGroupBasis.INSUFFICIENT -> "표본 부족"
            }
            Text(
                "${peer.groupLabel} · N=${peer.sampleSize} · $basisLabel",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(5.dp))

            if (peer.isSufficient && peer.median != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("중앙값", fontSize = 10.sp, color = LocalContentColor.current.copy(alpha = 0.72f))
                    Text(formatMetricValue(metric.id, peer.median), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text(peerComparisonText(metric, peer), fontSize = 10.sp, lineHeight = 15.sp)
            } else {
                Text(
                    "중앙값 미산출 · ${peer.reason ?: "충분한 동종업계 표본 없음"}",
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    color = LocalContentColor.current.copy(alpha = 0.76f)
                )
            }
        }
    }
}

private fun formatMetricValue(metricId: String, value: Double): String =
    if (metricId == "M03") String.format("%.2f배", value) else String.format("%.1f%%", value)

private fun peerComparisonText(metric: MetricValue, peer: PeerComparison): String {
    metric.rawValue ?: return "현재 종목 값이 없어 업종 중앙값과의 직접 비교는 보류합니다."
    val delta = peer.deltaFromMedian ?: return "업종 중앙값과의 직접 비교를 계산하지 못했습니다."
    if (abs(delta) <= 1e-12) return "업종 중앙값과 동일한 수준입니다."

    return if (metric.id == "M03") {
        val rel = peer.relativeToMedianPct
        val direction = if (delta < 0) "낮음" else "높음"
        val implication = if (peer.betterThanMedian == true) "정량 방향상 유리" else "정량 방향상 부담"
        if (rel != null && rel.isFinite()) {
            "업종 중앙값보다 ${String.format("%.1f", abs(rel))}% $direction · $implication"
        } else {
            "업종 중앙값보다 ${String.format("%.2f", abs(delta))}배 $direction · $implication"
        }
    } else {
        val sign = if (delta > 0) "+" else "-"
        val direction = if (delta > 0) "상회" else "하회"
        val implication = if (peer.betterThanMedian == true) "정량 방향상 우위" else "정량 방향상 열위"
        "업종 중앙값 대비 $sign${String.format("%.1f", abs(delta))}%p · $direction · $implication"
    }
}

@Composable
private fun RelativePositionBar(score: Double) {
    val scheme = MaterialTheme.colorScheme
    val fraction = (score / 100.0).coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(999.dp))
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = scheme.outlineVariant) {}
        Surface(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight(), color = scheme.primary) {}
    }
}

private fun relativeText(score: Double): String = when {
    score >= 90.0 -> "전체시장 기준 상위 10% 수준"
    score >= 75.0 -> "전체시장 기준 상위 25% 수준"
    score >= 50.0 -> "전체시장 기준 중상위 수준"
    score >= 25.0 -> "전체시장 기준 중하위 수준"
    else -> "전체시장 기준 하위 수준"
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, modifier = Modifier.width(36.dp), fontSize = 9.sp, color = LocalContentColor.current.copy(alpha = 0.70f))
        Text(value, modifier = Modifier.weight(1f), fontSize = 9.sp, color = LocalContentColor.current)
    }
}

@Composable
private fun FinanceSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(9.dp))
            content()
        }
    }
}

@Composable
private fun AnalysisCard(kicker: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Text(kicker, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
            Spacer(modifier = Modifier.height(2.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(9.dp))
            content()
        }
    }
}

@Composable
private fun InfoSection(title: String, content: String) {
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(content, fontSize = 12.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun BulletSection(title: String, items: List<String>) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(5.dp))
        items.forEach { item ->
            Text("• $item", fontSize = 12.sp, lineHeight = 19.sp)
        }
    }
}

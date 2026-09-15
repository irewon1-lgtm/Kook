package com.krstock.v3.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.analysis.IntegratedInterpretationEngine
import com.krstock.v3.data.analysis.QuarterlyTrendAnalyzer
import com.krstock.v3.data.evidence.ContextEvidenceRepository
import com.krstock.v3.data.history.QuarterlyHistoryRepository
import com.krstock.v3.data.model.*
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.ui.components.StatusBadge
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(issuerId: String, onBack: () -> Unit) {
    val stockDetail = remember(issuerId) { StockRepository.getStockDetail(issuerId) }
    val scheme = MaterialTheme.colorScheme

    if (stockDetail == null) {
        Scaffold(
            containerColor = scheme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
                    title = { Text("종목을 찾을 수 없음", fontWeight = FontWeight.Bold) },
                    navigationIcon = { TextButton(onClick = onBack) { Text("‹ 뒤로") } }
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

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val tabs = listOf("요약", "4지표", "종합해석", "체크·출처")

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = scheme.background,
                    titleContentColor = scheme.onBackground
                ),
                title = {
                    Column {
                        Text(
                            "${summary.name} (${summary.issuerId})",
                            modifier = Modifier.testTag("detail_title"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${summary.market} · ${summary.sector}",
                            fontSize = 10.sp,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ 뒤로") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                edgePadding = 8.dp,
                modifier = Modifier.fillMaxWidth().testTag("detail_tabs"),
                containerColor = scheme.surface,
                contentColor = scheme.onSurface,
                divider = { HorizontalDivider(color = scheme.outlineVariant) }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = {
                            Text(
                                title,
                                fontSize = 11.sp,
                                fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().testTag("detail_pager")
            ) { page ->
                when (page) {
                    0 -> DetailSummaryPage(summary = summary, analysis = integrated)
                    1 -> DetailMetricsPage(summary = summary)
                    2 -> DetailAnalysisPage(analysis = integrated, evidence = evidence, history = quarterlyHistory)
                    else -> DetailCheckSourcePage(report = report, evidence = evidence)
                }
            }
        }
    }
}

@Composable
private fun DetailSummaryPage(summary: StockSummary, analysis: IntegratedAnalysis) {
    val scheme = MaterialTheme.colorScheme
    val availableCount = listOf(summary.m01RevGrowth, summary.m02OpMargin, summary.m03Per, summary.m04Price6m)
        .count { it.isAvailable }

    PageColumn("detail_summary_page") {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer
            ),
            border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.22f)),
            shape = RoundedCornerShape(17.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("QUANT SNAPSHOT", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
                        Spacer(modifier = Modifier.height(5.dp))
                        Text("실데이터 $availableCount/4", fontWeight = FontWeight.Bold, fontSize = 21.sp)
                        Text(
                            if (summary.isCompositeComplete) "4지표 완성 · 순위 산출 가능" else "결측값은 추정하지 않고 보류",
                            fontSize = 10.sp,
                            color = scheme.onPrimaryContainer.copy(alpha = 0.74f)
                        )
                    }
                    StatusBadge(status = summary.status)
                }

                Spacer(modifier = Modifier.height(15.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryStat(
                        label = "기본 4지표 순위",
                        value = summary.rankOrder?.let { "${it}위" } ?: "보류",
                        modifier = Modifier.weight(1f)
                    )
                    SummaryStat(
                        label = "종합 상대점수",
                        value = summary.compositeScore?.let { String.format("%.1f점", it) } ?: "미산출",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

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
                IdentityRow("재무 기준", StockRepository.quantSnapshotDate())
                IdentityRow("주가 기준", StockRepository.priceCutoffDate())
            }
        }

        SwipeHint("← 밀어서 4대 정량지표 보기")
    }
}

@Composable
private fun SummaryStat(label: String, value: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(11.dp),
        color = scheme.surface.copy(alpha = 0.58f),
        contentColor = scheme.onPrimaryContainer,
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.16f))
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Text(label, fontSize = 9.sp, color = LocalContentColor.current.copy(alpha = 0.72f))
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DetailMetricsPage(summary: StockSummary) {
    val scheme = MaterialTheme.colorScheme
    PageColumn("detail_metrics_page") {
        Text(
            "4대 정량지표",
            modifier = Modifier.testTag("detail_metrics_header"),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Text(
            "값 · 업종 피어 중앙값 · 전체시장 상대위치 · 산출근거를 한 카드에서 확인합니다.",
            fontSize = 11.sp,
            color = scheme.onSurfaceVariant
        )
        MetricCard(summary.m01RevGrowth)
        MetricCard(summary.m02OpMargin)
        MetricCard(summary.m03Per, lossMaking = summary.isLossMaking)
        MetricCard(summary.m04Price6m)
        SwipeHint("← 밀어서 4지표 종합해석 보기")
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
                if (item.kind == EvidenceKind.DISCLOSURE) "공시" else "뉴스",
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
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
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

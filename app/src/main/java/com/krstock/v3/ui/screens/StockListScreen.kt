package com.krstock.v3.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.candidate.FinalCandidateRepository
import com.krstock.v3.data.model.FinancialSafetyStatus
import com.krstock.v3.data.model.StockSummary
import com.krstock.v3.data.ranking.DynamicRankResult
import com.krstock.v3.data.ranking.DynamicRankingEngine
import com.krstock.v3.data.ranking.RankMetric
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.stage.FinancialSafetyRepository
import com.krstock.v3.data.stage.ValuationBandPolicy
import com.krstock.v3.data.update.SnapshotAutoUpdater
import com.krstock.v3.ui.components.Stage45CompactPanel
import kotlinx.coroutines.launch

private enum class ListSortMode(val label: String) {
    COMBINATION("종합점수 순"), CODE("종목코드"), NAME("회사명")
}

private enum class SafetyFilter(val label: String) { ALL("전체"), HOLD("미확보만"), FAIL("재무위험만") }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StockListScreen(onStockClick: (String) -> Unit, onBack: () -> Unit, initialSearch: Boolean = false) {
    val snapshotState by SnapshotAutoUpdater.state.collectAsState()
    val stocks = remember(snapshotState.revision) { StockRepository.getAllStocks() }
    val snapshotDate = stocks.firstOrNull()?.asOfDate ?: snapshotState.snapshotDate
    val priceDate = stocks.firstOrNull()?.m04Price6m?.asOfDate ?: snapshotState.priceCutoffDate
    val candidates = remember(stocks, snapshotState.revision) { FinalCandidateRepository.getFinalCandidateMap() }
    val safety = remember(stocks, snapshotState.revision) { FinancialSafetyRepository.getAll() }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(initialSearch) }
    var selectedMarket by remember { mutableStateOf("전체") }
    var completeOnly by remember { mutableStateOf(false) }
    var safetyFilter by remember { mutableStateOf(SafetyFilter.ALL) }
    var sortMode by remember { mutableStateOf(ListSortMode.COMBINATION) }
    var selectedMetricIds by remember { mutableStateOf(RankMetric.allIds) }
    var showMetrics by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scheme = MaterialTheme.colorScheme
    val dynamicRanks = remember(stocks, selectedMetricIds) { DynamicRankingEngine.rank(stocks, selectedMetricIds) }
    val rankedCount = remember(dynamicRanks) { dynamicRanks.values.count { it.isEligible } }
    val filteredStocks = remember(stocks, searchQuery, selectedMarket, completeOnly, safetyFilter, sortMode, dynamicRanks, safety) {
        val query = searchQuery.trim()
        stocks.asSequence()
            .filter { query.isEmpty() || it.name.contains(query, true) || it.issuerId.contains(query, true) || it.sector.contains(query, true) || it.market.contains(query, true) }
            .filter { selectedMarket == "전체" || it.market == selectedMarket }
            .filter { !completeOnly || dynamicRanks[it.issuerId]?.isEligible == true }
            .filter {
                when (safetyFilter) {
                    SafetyFilter.ALL -> true
                    SafetyFilter.HOLD -> safety[it.issuerId]?.status == FinancialSafetyStatus.HOLD || safety[it.issuerId] == null
                    SafetyFilter.FAIL -> safety[it.issuerId]?.status == FinancialSafetyStatus.FAIL
                }
            }
            .sortedWith(sortComparator(sortMode, dynamicRanks)).toList()
    }
    val selectedCount = selectedMetricIds.size
    val selectedNames = RankMetric.entries.filter { it.id in selectedMetricIds }.joinToString(" · ") { it.label }
    val weightLabel = when (selectedCount) { 1 -> "100%"; 2 -> "각 50%"; 3 -> "각 33.3%"; else -> "각 25%" }
    val eligibleShown = filteredStocks.count { dynamicRanks[it.issuerId]?.isEligible == true }

    LaunchedEffect(initialSearch) { if (initialSearch) showSearch = true }
    LaunchedEffect(showSearch) {
        if (showSearch) {
            listState.scrollToItem(0)
            focusRequester.requestFocus()
            if (initialSearch) keyboard?.show()
        }
    }

    Column(Modifier.fillMaxSize().background(scheme.background)) {
        NavyScreenHeader(onSearch = { showSearch = !showSearch })
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("stock_rank_list"),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 24.dp),
        ) {
            // Keep all controls in one scrollable item. Every stock remains a direct lazy item.
            item(key = "ranking_controls") {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("종목 순위", Modifier.testTag("ranking_title"), fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
                            Text("${selectedCount}지표 기반 종목의 종합 상대점수 순위입니다.", fontSize = 10.sp, lineHeight = 14.sp, color = scheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { showInfo = true }, modifier = Modifier.size(29.dp).testTag("ranking_info")) { Text("ⓘ", fontSize = 20.sp, color = scheme.onSurfaceVariant) }
                    }
                    Spacer(Modifier.height(4.dp))
                    RankingSnapshotPanel(stocks.size, stocks.count { it.isCompositeComplete }, snapshotDate, priceDate, snapshotState)
                    if (showSearch) {
                        Spacer(Modifier.height(7.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("stock_search"),
                            placeholder = { Text("회사명 · 종목코드 · 업종 검색", fontSize = 12.sp) },
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Text("×") } },
                            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = scheme.surface, unfocusedContainerColor = scheme.surface, unfocusedBorderColor = scheme.outline),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("전체", "KOSPI", "KOSDAQ").forEach { market ->
                            CompactFilter(market, selectedMarket == market, { selectedMarket = market }, Modifier.testTag("market_$market"))
                        }
                        Spacer(Modifier.weight(1f))
                        CompactFilter("${selectedCount}지표  ${if (showMetrics) "⌃" else "⌄"}", showMetrics, { showMetrics = !showMetrics }, Modifier.testTag("metric_picker"))
                    }
                    if (showMetrics) {
                        Surface(Modifier.fillMaxWidth().padding(top = 5.dp), color = scheme.surface, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, scheme.outlineVariant)) {
                            Column(Modifier.padding(9.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("비교할 지표", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("${selectedCount}개 · $weightLabel", fontSize = 10.sp, color = scheme.primary)
                                }
                                Text(selectedNames, Modifier.padding(top = 3.dp), fontSize = 10.sp, color = scheme.onSurfaceVariant)
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    RankMetric.entries.forEach { metric ->
                                        CompactFilter(metric.label, metric.id in selectedMetricIds, {
                                            selectedMetricIds = when {
                                                metric.id in selectedMetricIds && selectedMetricIds.size == 1 -> selectedMetricIds
                                                metric.id in selectedMetricIds -> selectedMetricIds - metric.id
                                                else -> selectedMetricIds + metric.id
                                            }
                                        }, Modifier.testTag("metric_toggle_${metric.id}"))
                                    }
                                }
                                CompactFilter("선택지표 완성 종목만", completeOnly, { completeOnly = !completeOnly }, Modifier.testTag("selected_metrics_complete_only"))
                            }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box {
                            CompactFilter("↕ ${sortMode.label}  ⌄", false, { showSort = true }, Modifier.testTag("sort_picker"))
                            DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                                ListSortMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(if (sortMode == mode) "✓ ${mode.label}" else mode.label, fontSize = 12.sp) },
                                        onClick = { sortMode = mode; showSort = false },
                                        modifier = Modifier.testTag("sort_${mode.name}").semantics { selected = sortMode == mode },
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SafetyFilter.entries.forEach { filter ->
                                CompactFilter(filter.label, safetyFilter == filter, { safetyFilter = filter }, Modifier.testTag("safety_filter_${filter.name}"), small = true)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    NavyUniverseCounts(stocks.size, rankedCount, inline = true)
                    if (searchQuery.isNotBlank() || selectedMarket != "전체" || completeOnly || safetyFilter != SafetyFilter.ALL) {
                        Text("검색 결과 ${filteredStocks.size}개 종목 · 순위 가능 ${eligibleShown}개", Modifier.padding(horizontal = 3.dp, vertical = 5.dp).testTag("ranking_result_count"), fontSize = 10.sp, color = scheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                    StockTableHeading(showMetrics = false)
                }
            }
            if (filteredStocks.isEmpty()) {
                item(key = "empty_result") {
                    Surface(Modifier.fillMaxWidth(), color = scheme.surface) {
                        Column(Modifier.padding(20.dp)) {
                            Text("검색 결과가 없습니다.", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("검색어·시장·완성 조건을 바꿔보세요.", Modifier.padding(top = 5.dp), fontSize = 12.sp, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                itemsIndexed(filteredStocks, key = { _, stock -> "${stock.market}:${stock.issuerId}" }) { index, stock ->
                    val dynamic = dynamicRanks[stock.issuerId]
                    CompositionLocalProvider(LocalStockRowMetrics provides false) {
                        StockSummaryCard(
                            stock = stock,
                            onClick = { onStockClick(stock.issuerId) },
                            financialSafety = safety[stock.issuerId],
                            displayRank = dynamic?.rank,
                            displayScore = dynamic?.score,
                            scoreLabel = "선택 ${selectedCount}개 지표 종합 상대점수",
                            missingLabel = "선택지표 결측 · 조합순위 보류",
                            stage45Content = { Stage45CompactPanel(candidates[stock.issuerId], safety[stock.issuerId], ValuationBandPolicy.classify(stock.m03Per), showRatios = false) },
                        )
                    }
                    if (index == 9 && filteredStocks.size > 10) {
                        Spacer(Modifier.height(7.dp))
                        NavyFullListAction("전체 순위 보기 (${eligibleShown}종목)  ›", "ranking_full_list_action") {
                            scope.launch { listState.animateScrollToItem(11) }
                        }
                        Spacer(Modifier.height(40.dp))
                    }
                }
            }
        }
    }
    if (showInfo) AlertDialog(
        onDismissRequest = { showInfo = false },
        title = { Text("국내주식 조합순위") },
        text = { Text("성장·수익성·가치·흐름 중 선택한 ${selectedCount}개 지표의 시장 내 상대점수를 동일 비중($weightLabel)으로 비교합니다.\n\n${selectedNames}\n\n선택한 지표가 없는 종목도 목록에 공개하며 순위는 보류합니다. 재무자료 상태는 점수와 순위에 영향을 주지 않습니다.", fontSize = 13.sp, lineHeight = 20.sp) },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text("닫기") } },
        dismissButton = { TextButton(onClick = { showInfo = false; onBack() }) { Text("홈으로") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactFilter(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = onClick,
            modifier = modifier.heightIn(min = 23.dp).semantics { this.selected = selected },
            shape = RoundedCornerShape(10.dp),
            color = if (selected) scheme.primary else scheme.background,
            contentColor = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
            border = BorderStroke(1.dp, if (selected) scheme.primary else scheme.outline),
        ) {
            Text(label, Modifier.padding(horizontal = if (small) 9.dp else 11.dp, vertical = 4.dp), fontSize = if (small) 10.sp else 11.sp, lineHeight = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun RankingSnapshotPanel(total: Int, complete: Int, snapshotDate: String, priceDate: String, status: SnapshotAutoUpdater.Result) {
    val scheme = MaterialTheme.colorScheme
    val matches = status.snapshotDate == snapshotDate && status.priceCutoffDate == priceDate
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val inlineDates = configuration.screenWidthDp >= 600 ||
        (configuration.screenWidthDp >= 360 && configuration.fontScale <= 1.15f)
    val dateText = "재무 $snapshotDate · 주가 $priceDate"
    Surface(Modifier.fillMaxWidth(), color = scheme.primaryContainer.copy(alpha = .4f), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, scheme.outline)) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SnapshotSourceHeading(status, matches, "ranking_snapshot_source")
                if (inlineDates) {
                    Spacer(Modifier.weight(1f))
                    Text(dateText, Modifier.testTag("ranking_snapshot_dates"), fontSize = 8.sp, lineHeight = 11.sp, color = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1.3f)) {
                    SnapshotMetadata("refresh", if (matches) "최근 갱신 ${snapshotTimeText(status.refreshedAt)}" else "최근 갱신 확인 중")
                    SnapshotMetadata("check", "최근 확인 ${snapshotTimeText(status.lastAttemptAt)}")
                }
                Box(Modifier.padding(horizontal = 8.dp).width(.5.dp).height(24.dp).background(scheme.outline))
                Column(Modifier.weight(1f)) {
                    SnapshotMetadata("layers", "4지표 완성 $complete/$total", "ranking_snapshot_count")
                    Text("전체 종목 ${String.format("%,d", total)}종목", fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (!inlineDates) Text(dateText, Modifier.padding(top = 2.dp).testTag("ranking_snapshot_dates"), fontSize = 8.sp, lineHeight = 11.sp, color = scheme.onSurfaceVariant)
            if (matches && status.failure != null) Text("갱신 실패 · 마지막 정상 자료를 표시합니다", Modifier.testTag("ranking_snapshot_failure"), fontSize = 9.sp, lineHeight = 12.sp, color = scheme.error)
        }
    }
}

private fun sortComparator(mode: ListSortMode, dynamicRanks: Map<String, DynamicRankResult>): Comparator<StockSummary> = when (mode) {
    ListSortMode.COMBINATION -> compareBy<StockSummary> { dynamicRanks[it.issuerId]?.rank == null }.thenBy { dynamicRanks[it.issuerId]?.rank ?: Int.MAX_VALUE }.thenBy { it.issuerId }
    ListSortMode.CODE -> compareBy<StockSummary> { it.issuerId }.thenBy { it.market }
    ListSortMode.NAME -> compareBy<StockSummary> { it.name }.thenBy { it.issuerId }
}

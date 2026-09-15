package com.krstock.v3.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.model.StockSummary
import com.krstock.v3.data.ranking.DynamicRankResult
import com.krstock.v3.data.ranking.DynamicRankingEngine
import com.krstock.v3.data.ranking.RankMetric
import com.krstock.v3.data.repository.StockRepository

private enum class ListSortMode(val label: String) {
    COMBINATION("선택지표 순위"),
    CODE("종목코드"),
    NAME("회사명")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockListScreen(
    onStockClick: (String) -> Unit,
    onBack: () -> Unit
) {
    val stocks = remember { StockRepository.getAllStocks() }
    var searchQuery by remember { mutableStateOf("") }
    var selectedMarket by remember { mutableStateOf("전체") }
    var selectedCompleteOnly by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(ListSortMode.COMBINATION) }
    var selectedMetricIds by remember { mutableStateOf(RankMetric.allIds) }
    val listState = rememberLazyListState()

    val dynamicRanks = remember(stocks, selectedMetricIds) {
        DynamicRankingEngine.rank(stocks, selectedMetricIds)
    }

    val filteredStocks = remember(
        searchQuery,
        selectedMarket,
        selectedCompleteOnly,
        sortMode,
        dynamicRanks
    ) {
        StockRepository.searchStocks(searchQuery)
            .asSequence()
            .filter { selectedMarket == "전체" || it.market == selectedMarket }
            .filter { !selectedCompleteOnly || dynamicRanks[it.issuerId]?.isEligible == true }
            .sortedWith(sortComparator(sortMode, dynamicRanks))
            .toList()
    }

    // A ranking/filter mode change represents a new result set. Never preserve the
    // previous rank-list viewport: show the new first row immediately.
    // Search typing is intentionally excluded so the keyboard/input field stays stable.
    LaunchedEffect(
        selectedMetricIds,
        selectedMarket,
        selectedCompleteOnly,
        sortMode
    ) {
        if (filteredStocks.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    val selectedCount = selectedMetricIds.size
    val selectedNames = RankMetric.entries
        .filter { it.id in selectedMetricIds }
        .joinToString(" · ") { it.label }
    val weightLabel = when (selectedCount) {
        1 -> "100%"
        2 -> "각 50%"
        3 -> "각 33.3%"
        else -> "각 25%"
    }
    val eligibleShown = filteredStocks.count { dynamicRanks[it.issuerId]?.isEligible == true }
    val scheme = MaterialTheme.colorScheme

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
                            "국내주식 조합순위",
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            lineHeight = 27.sp
                        )
                        Text(
                            "지표를 바꾸면 새 순위의 1위부터 바로 표시",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = scheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("‹ 홈", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("stock_search"),
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { Text("회사명 · 종목코드 · 업종 검색", fontSize = 15.sp) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = scheme.surface,
                    unfocusedContainerColor = scheme.surface,
                    focusedBorderColor = scheme.primary,
                    unfocusedBorderColor = scheme.outlineVariant
                ),
                supportingText = null
            )

            Spacer(modifier = Modifier.height(10.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surface),
                border = BorderStroke(1.dp, scheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("랭킹 지표", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(
                                selectedNames,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = scheme.primaryContainer,
                            contentColor = scheme.onPrimaryContainer
                        ) {
                            Text(
                                "${selectedCount}개 · $weightLabel",
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(9.dp))
                    RankMetric.entries.chunked(2).forEachIndexed { index, pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pair.forEach { metric ->
                                val selected = metric.id in selectedMetricIds
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedMetricIds = when {
                                            selected && selectedMetricIds.size == 1 -> selectedMetricIds
                                            selected -> selectedMetricIds - metric.id
                                            else -> selectedMetricIds + metric.id
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 46.dp)
                                        .testTag(metricToggleTestTag(metric.id)),
                                    shape = RoundedCornerShape(13.dp),
                                    label = {
                                        Text(
                                            if (selected) "✓ ${metric.label}" else metric.label,
                                            fontSize = 13.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                            if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                        if (index < RankMetric.entries.chunked(2).lastIndex) {
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(9.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surface),
                border = BorderStroke(1.dp, scheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        listOf("전체", "KOSPI", "KOSDAQ").forEach { market ->
                            val selected = selectedMarket == market
                            FilterChip(
                                selected = selected,
                                onClick = { selectedMarket = market },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp)
                                    .testTag("market_$market"),
                                shape = RoundedCornerShape(13.dp),
                                label = {
                                    Text(
                                        market,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    FilterChip(
                        selected = selectedCompleteOnly,
                        onClick = { selectedCompleteOnly = !selectedCompleteOnly },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .testTag("selected_metrics_complete_only"),
                        shape = RoundedCornerShape(13.dp),
                        label = {
                            Text(
                                if (selectedCompleteOnly) "✓ 선택지표 완성 종목만" else "선택지표 완성 종목만",
                                fontSize = 13.sp,
                                fontWeight = if (selectedCompleteOnly) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        ListSortMode.entries.forEach { mode ->
                            val selected = sortMode == mode
                            FilterChip(
                                selected = selected,
                                onClick = { sortMode = mode },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 46.dp)
                                    .testTag("sort_${mode.name}"),
                                shape = RoundedCornerShape(13.dp),
                                label = {
                                    Text(
                                        if (selected) "✓ ${mode.label}" else mode.label,
                                        fontSize = 12.sp,
                                        lineHeight = 15.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${filteredStocks.size}개 종목",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    "순위 가능 ${eligibleShown}개",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(7.dp))

            if (filteredStocks.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    border = BorderStroke(1.dp, scheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(22.dp)) {
                        Text("검색 결과가 없습니다.", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(5.dp))
                        Text(
                            "검색어·시장·완성 조건을 바꿔보세요.",
                            fontSize = 13.sp,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("stock_rank_list"),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredStocks, key = { "${it.market}:${it.issuerId}" }) { stock ->
                        val dynamic = dynamicRanks[stock.issuerId]
                        StockSummaryCard(
                            stock = stock,
                            onClick = { onStockClick(stock.issuerId) },
                            displayRank = dynamic?.rank,
                            displayScore = dynamic?.score,
                            scoreLabel = "선택 ${selectedCount}개 지표 종합 상대점수",
                            missingLabel = "선택지표 결측 · 조합순위 보류"
                        )
                    }
                }
            }
        }
    }
}

private fun metricToggleTestTag(metricId: String): String = when (metricId) {
    "M01" -> "metric_toggle_M01"
    "M02" -> "metric_toggle_M02"
    "M03" -> "metric_toggle_M03"
    "M04" -> "metric_toggle_M04"
    else -> error("Unknown metric toggle $metricId")
}

private fun sortComparator(
    mode: ListSortMode,
    dynamicRanks: Map<String, DynamicRankResult>
): Comparator<StockSummary> = when (mode) {
    ListSortMode.COMBINATION -> compareBy<StockSummary> { dynamicRanks[it.issuerId]?.rank == null }
        .thenBy { dynamicRanks[it.issuerId]?.rank ?: Int.MAX_VALUE }
        .thenBy { it.issuerId }
    ListSortMode.CODE -> compareBy<StockSummary> { it.issuerId }.thenBy { it.market }
    ListSortMode.NAME -> compareBy<StockSummary> { it.name }.thenBy { it.issuerId }
}

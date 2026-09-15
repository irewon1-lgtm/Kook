package com.krstock.v3.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.model.StockSummary
import com.krstock.v3.data.ranking.DynamicRankResult
import com.krstock.v3.data.ranking.DynamicRankingEngine
import com.krstock.v3.data.ranking.RankMetric
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.ui.theme.BlueAccent
import com.krstock.v3.ui.theme.BlueAccentSoft
import com.krstock.v3.ui.theme.BorderLight
import com.krstock.v3.ui.theme.SurfaceMuted
import com.krstock.v3.ui.theme.TextSecondaryLight
import com.krstock.v3.ui.theme.TextTertiaryLight

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

    val selectedCount = selectedMetricIds.size
    val selectedNames = RankMetric.entries.filter { it.id in selectedMetricIds }.joinToString(" · ") { it.label }
    val eligibleShown = filteredStocks.count { dynamicRanks[it.issuerId]?.isEligible == true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("국내주식 조합순위", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text("원하는 지표만 골라 즉시 재순위", fontSize = 11.sp, color = TextSecondaryLight)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ 홈") } }
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
                    .testTag("stock_search"),
                placeholder = { Text("회사명 · 종목코드 · 업종 검색") },
                singleLine = true,
                shape = RoundedCornerShape(13.dp),
                supportingText = null
            )

            Spacer(modifier = Modifier.height(10.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, BorderLight)
            ) {
                Column(modifier = Modifier.padding(13.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("순위에 넣을 지표", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("1~4개 · 선택 지표 동일가중", fontSize = 10.sp, color = TextSecondaryLight)
                        }
                        Surface(shape = RoundedCornerShape(999.dp), color = BlueAccentSoft) {
                            Text(
                                "${selectedCount}개 선택",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = BlueAccent
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    RankMetric.entries.chunked(2).forEach { pair ->
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
                                        .testTag(metricToggleTestTag(metric.id)),
                                    label = {
                                        Text(
                                            if (selected) "✓ ${metric.label}" else metric.label,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }
                                )
                            }
                            if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("active_combo_summary"),
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceMuted,
                        border = BorderStroke(1.dp, BorderLight)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("선택 ${selectedCount}개 지표 종합순위", fontWeight = FontWeight.Bold, color = BlueAccent, fontSize = 12.sp)
                                Text(selectedNames, fontSize = 10.sp, color = TextSecondaryLight, maxLines = 1)
                            }
                            Text(
                                when (selectedCount) {
                                    1 -> "선택 지표 상대점수 100%"
                                    2 -> "각 지표 50%"
                                    3 -> "각 지표 약 33.3%"
                                    else -> "각 지표 25%"
                                },
                                fontSize = 10.sp,
                                color = TextSecondaryLight
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                listOf("전체", "KOSPI", "KOSDAQ").forEach { market ->
                    FilterChip(
                        selected = selectedMarket == market,
                        onClick = { selectedMarket = market },
                        modifier = Modifier.testTag("market_$market"),
                        label = { Text(market, fontSize = 11.sp) }
                    )
                }
                FilterChip(
                    selected = selectedCompleteOnly,
                    onClick = { selectedCompleteOnly = !selectedCompleteOnly },
                    modifier = Modifier.testTag("selected_metrics_complete_only"),
                    label = { Text("선택지표 완성만", fontSize = 11.sp) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("${filteredStocks.size}개 종목", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("현재 조합 순위 가능 ${eligibleShown}개", fontSize = 10.sp, color = TextTertiaryLight)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ListSortMode.entries.forEach { mode ->
                        AssistChip(
                            onClick = { sortMode = mode },
                            label = { Text(if (sortMode == mode) "✓ ${mode.label}" else mode.label, fontSize = 9.sp) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(5.dp))

            if (filteredStocks.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, BorderLight)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("검색 결과가 없습니다.", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "검색어·시장·선택지표 완성 조건을 바꿔보세요.",
                            fontSize = 12.sp,
                            color = TextSecondaryLight
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.testTag("stock_rank_list"),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
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

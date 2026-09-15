package com.krstock.v3.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.krstock.v3.ui.theme.TextSecondaryLight

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
                        Text("국내주식 조합순위", fontWeight = FontWeight.Bold)
                        Text("1~4개 지표를 동시에 선택해서 새 순위를 계산", fontSize = 11.sp, color = TextSecondaryLight)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("< 홈") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().testTag("stock_search"),
                placeholder = { Text("회사명 · 종목코드 · 업종 검색") },
                singleLine = true,
                supportingText = {
                    Text(
                        "${filteredStocks.size}개 표시 · 선택 ${selectedCount}개 지표 순위가능 ${eligibleShown}개",
                        fontSize = 10.sp
                    )
                }
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text("순위에 넣을 지표", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "최소 1개 선택 · 선택한 지표만 동일가중 평균",
                fontSize = 10.sp,
                color = TextSecondaryLight
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RankMetric.entries.forEach { metric ->
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
                        modifier = Modifier.testTag(metricToggleTestTag(metric.id)),
                        label = { Text(if (selected) "✓ ${metric.label}" else metric.label, fontSize = 11.sp) }
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().testTag("active_combo_summary"),
                colors = CardDefaults.cardColors(containerColor = BlueAccent.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text("선택 ${selectedCount}개 지표 종합순위", fontWeight = FontWeight.Bold, color = BlueAccent, fontSize = 13.sp)
                    Text(selectedNames, fontSize = 10.sp, color = TextSecondaryLight)
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

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("전체", "KOSPI", "KOSDAQ").forEach { market ->
                    FilterChip(
                        selected = selectedMarket == market,
                        onClick = { selectedMarket = market },
                        modifier = Modifier.testTag("market_$market"),
                        label = { Text(market) }
                    )
                }
                FilterChip(
                    selected = selectedCompleteOnly,
                    onClick = { selectedCompleteOnly = !selectedCompleteOnly },
                    modifier = Modifier.testTag("selected_metrics_complete_only"),
                    label = { Text("선택지표 완성만") }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ListSortMode.entries.forEach { mode ->
                    AssistChip(
                        onClick = { sortMode = mode },
                        label = { Text(if (sortMode == mode) "✓ ${mode.label}" else mode.label, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredStocks.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("검색 결과가 없습니다.", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "검색어·시장·선택지표 완성 조건을 바꿔보세요. 없는 종목을 다른 종목으로 대신 표시하지 않습니다.",
                            fontSize = 12.sp,
                            color = TextSecondaryLight
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.testTag("stock_rank_list"),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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

package com.krstock.v3.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.model.StockSummary
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.ui.theme.TextSecondaryLight

private enum class SortMode(val label: String) {
    RANK("종합순위"),
    NAME("이름"),
    GROWTH("매출성장"),
    MARGIN("영업이익률"),
    PER("PER 상대점수"),
    MOMENTUM("6개월 상승률")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockListScreen(
    onStockClick: (String) -> Unit,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedMarket by remember { mutableStateOf("전체") }
    var completeOnly by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(SortMode.RANK) }

    val filteredStocks = remember(searchQuery, selectedMarket, completeOnly, sortMode) {
        StockRepository.searchStocks(searchQuery)
            .asSequence()
            .filter { selectedMarket == "전체" || it.market == selectedMarket }
            .filter { !completeOnly || it.isCompositeComplete }
            .sortedWith(sortComparator(sortMode))
            .toList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("국내주식 후보 목록", fontWeight = FontWeight.Bold)
                        Text("검색 · 시장 · 결측 · 정렬", fontSize = 11.sp, color = TextSecondaryLight)
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("< 뒤로")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("종목명 · 코드 · 업종 검색") },
                singleLine = true,
                supportingText = {
                    Text("현재 ${filteredStocks.size}개 표시 · 내장 데이터는 DEMO", fontSize = 10.sp)
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("전체", "KOSPI", "KOSDAQ").forEach { market ->
                    FilterChip(
                        selected = selectedMarket == market,
                        onClick = { selectedMarket = market },
                        label = { Text(market) }
                    )
                }
                FilterChip(
                    selected = completeOnly,
                    onClick = { completeOnly = !completeOnly },
                    label = { Text("4지표 완성만") }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SortMode.entries.forEach { mode ->
                    AssistChip(
                        onClick = { sortMode = mode },
                        label = {
                            Text(
                                if (sortMode == mode) "✓ ${mode.label}" else mode.label,
                                fontSize = 11.sp
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (filteredStocks.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("검색 결과가 없습니다.", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "검색어·시장 필터·4지표 완성 조건을 바꿔보세요. 없는 종목을 다른 종목으로 대신 보여주지 않습니다.",
                            fontSize = 12.sp,
                            color = TextSecondaryLight
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredStocks, key = { it.issuerId }) { stock ->
                        StockSummaryCard(stock = stock, onClick = { onStockClick(stock.issuerId) })
                    }
                }
            }
        }
    }
}

private fun sortComparator(mode: SortMode): Comparator<StockSummary> {
    return when (mode) {
        SortMode.RANK -> compareBy<StockSummary> { it.rankOrder == null }
            .thenBy { it.rankOrder ?: Int.MAX_VALUE }
            .thenBy { it.issuerId }
        SortMode.NAME -> compareBy { it.name }
        SortMode.GROWTH -> compareByDescending<StockSummary> { it.m01RevGrowth.rawValue ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.issuerId }
        SortMode.MARGIN -> compareByDescending<StockSummary> { it.m02OpMargin.rawValue ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.issuerId }
        SortMode.PER -> compareByDescending<StockSummary> { it.m03Per.percentileScore ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.issuerId }
        SortMode.MOMENTUM -> compareByDescending<StockSummary> { it.m04Price6m.rawValue ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.issuerId }
    }
}

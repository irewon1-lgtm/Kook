package com.krstock.v3.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.model.StockSummary
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.ui.components.StatusBadge
import com.krstock.v3.ui.theme.BlueAccent
import com.krstock.v3.ui.theme.TextSecondaryLight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStockClick: (String) -> Unit,
    onNavigateToList: () -> Unit
) {
    val stocks = remember { StockRepository.getAllStocks() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "국내주식 V3 — 4지표 후보 순위",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = BlueAccent.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "조사할 가치가 높은 국내주식 후보 찾기",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = BlueAccent
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "단기 급등주 예측이 아니라 4개 정량지표(매출성장률, 영업이익률, 실적PER, 6M상승률) 25% 가중치 상대평가 점수를 기반으로 조사 우선순위를 제시합니다.",
                            fontSize = 13.sp,
                            color = TextSecondaryLight
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "상위 후보 종목",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    TextButton(onClick = onNavigateToList) {
                        Text("전체보기 >")
                    }
                }
            }

            items(stocks) { stock ->
                StockSummaryCard(stock = stock, onClick = { onStockClick(stock.issuerId) })
            }
        }
    }
}

@Composable
fun StockSummaryCard(stock: StockSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stock.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stock.issuerId,
                        fontSize = 12.sp,
                        color = TextSecondaryLight
                    )
                }
                StatusBadge(status = stock.status)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("매출증가율", fontSize = 11.sp, color = TextSecondaryLight)
                    Text(
                        text = stock.m01RevGrowth.rawValue?.let { "${it}%" } ?: "N/A",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
                Column {
                    Text("영업이익률", fontSize = 11.sp, color = TextSecondaryLight)
                    Text(
                        text = stock.m02OpMargin.rawValue?.let { "${it}%" } ?: "N/A",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
                Column {
                    Text("실적 PER", fontSize = 11.sp, color = TextSecondaryLight)
                    Text(
                        text = stock.m03Per.rawValue?.let { "${it}배" } ?: "N/A",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
                Column {
                    Text("6M 상승률", fontSize = 11.sp, color = TextSecondaryLight)
                    Text(
                        text = stock.m04Price6m.rawValue?.let { "${it}%" } ?: "N/A",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }

            Divider(modifier = Modifier.padding(vertical = 10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (stock.isCompositeComplete) "우선 조사 후보 순위 점수" else "종합점수 미완성 (결측치 존재)",
                    fontSize = 12.sp,
                    color = TextSecondaryLight
                )
                Text(
                    text = stock.compositeScore?.let { String.format("%.2f점", it) } ?: "미완성",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = BlueAccent
                )
            }
        }
    }
}

package com.krstock.v3.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    val previewStocks = remember(stocks) {
        val preferred = listOf("005930", "000660", "035420", "005380", "000250", "086520", "247540", "035900")
        (preferred.mapNotNull { code -> stocks.find { it.issuerId == code } } + stocks.take(8))
            .distinctBy { it.issuerId }
            .take(8)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("KR4 국내주식", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text("KOSPI · KOSDAQ 실종목 등록 단계", fontSize = 11.sp, color = TextSecondaryLight)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .testTag("home_list"),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(2.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = BlueAccent.copy(alpha = 0.09f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("KOSPI·KOSDAQ 실종목 등록 완료", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = BlueAccent)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "KRX KIND 상장법인 목록 기준으로 종목명 · 종목코드 · 시장 · 업종 · 상장일을 등록했습니다. 정량지표는 검증된 실데이터 연결 전이라 아직 비워둡니다.",
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SummaryPill("전체 등록", "${stocks.size}개", Modifier.weight(1f))
                            SummaryPill("KOSPI", "${StockRepository.kospiCount()}개", Modifier.weight(1f))
                            SummaryPill("KOSDAQ", "${StockRepository.kosdaqCount()}개", Modifier.weight(1f))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("4지표 완성 0개 · 점수/순위 미산출", fontSize = 11.sp, color = TextSecondaryLight)
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("현재 단계에서 확인된 것", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        RegistrationRow("실제 상장 종목", "KRX KIND KOSPI · KOSDAQ")
                        RegistrationRow("종목 식별정보", "코드 · 회사명 · 시장 · 업종 · 상장일")
                        RegistrationRow("4개 정량지표", "아직 미수집 · 가짜값 입력 안 함")
                        RegistrationRow("순위", "지표 연결 전이므로 아직 미산출")
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("등록 종목 미리보기", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("REGISTERED = 실종목 확인 · 지표 수집 전", fontSize = 11.sp, color = TextSecondaryLight)
                    }
                    TextButton(onClick = onNavigateToList) { Text("전체보기 >") }
                }
            }

            items(previewStocks, key = { "${it.market}:${it.issuerId}" }) { stock ->
                StockSummaryCard(stock = stock, onClick = { onStockClick(stock.issuerId) })
            }

            item {
                OutlinedButton(
                    onClick = onNavigateToList,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("KOSPI·KOSDAQ 전체 실종목 검색")
                }
            }

            item {
                Text(
                    "마스터 기준일: ${StockRepository.masterSnapshotDate()} · 출처: KRX KIND",
                    fontSize = 10.sp,
                    color = TextSecondaryLight
                )
            }
        }
    }
}

@Composable
private fun SummaryPill(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(label, fontSize = 10.sp, color = TextSecondaryLight)
        }
    }
}

@Composable
private fun RegistrationRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(value, fontSize = 11.sp, color = TextSecondaryLight)
    }
}

@Composable
fun StockSummaryCard(stock: StockSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stock.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stock.issuerId, fontSize = 11.sp, color = TextSecondaryLight)
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text("${stock.market} · ${stock.sector}", fontSize = 11.sp, color = TextSecondaryLight)
                    if (stock.listingDate.isNotBlank()) {
                        Text("상장일 ${stock.listingDate}", fontSize = 10.sp, color = TextSecondaryLight)
                    }
                }
                StatusBadge(status = stock.status)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CompactMetric("매출증가", "미수집")
                CompactMetric("영업마진", "미수집")
                CompactMetric("PER", "미수집")
                CompactMetric("6개월", "미수집")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("${stock.market} 실종목 등록 완료 · 정량지표 연결 전", fontSize = 11.sp, color = TextSecondaryLight)
        }
    }
}

@Composable
private fun CompactMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = TextSecondaryLight)
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

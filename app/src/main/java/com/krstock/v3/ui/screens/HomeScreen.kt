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
    val rankedStocks = remember(stocks) { stocks.filter { it.rankOrder != null }.sortedBy { it.rankOrder } }
    val previewStocks = remember(stocks, rankedStocks) {
        if (rankedStocks.isNotEmpty()) rankedStocks.take(8)
        else {
            val preferred = listOf("005930", "000660", "035420", "005380", "000250", "086520", "247540", "035900")
            preferred.mapNotNull { code -> stocks.find { it.issuerId == code } }.take(8)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("KR4 국내주식", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text("OpenDART · 주가 실데이터 4지표", fontSize = 11.sp, color = TextSecondaryLight)
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
                        Text("실데이터 4지표 연결 완료", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = BlueAccent)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "매출 증가율·영업이익률은 OpenDART, 실적 PER·6개월 상승률은 실제 가격/EPS 자료에서 계산합니다. 자료가 없는 항목은 추정하지 않고 보류합니다.",
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SummaryPill("전체", "${stocks.size}개", Modifier.weight(1f))
                            SummaryPill("실데이터", "${StockRepository.realDataCount()}개", Modifier.weight(1f))
                            SummaryPill("4지표 완성", "${StockRepository.completeCount()}개", Modifier.weight(1f))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "재무 기준 ${StockRepository.quantSnapshotDate()} · 주가 마감 기준 ${StockRepository.priceCutoffDate()}",
                            fontSize = 10.sp,
                            color = TextSecondaryLight
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("현재 데이터 상태", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        RegistrationRow("KOSPI", "${StockRepository.kospiCount()}개")
                        RegistrationRow("KOSDAQ", "${StockRepository.kosdaqCount()}개")
                        RegistrationRow("매출 증가율", "2,469개 실데이터")
                        RegistrationRow("영업이익률", "2,380개 실데이터")
                        RegistrationRow("실적 PER", "1,677개 실데이터")
                        RegistrationRow("6개월 상승률", "2,611개 실데이터")
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("4지표를 읽는 법", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        MetricGuideRow("① 매출 증가율", "전년 같은 기간보다 매출이 커졌나", "높을수록 유리")
                        MetricGuideRow("② 영업이익률", "본업에서 얼마나 남기나", "높을수록 유리")
                        MetricGuideRow("③ 실적 PER", "실제 이익 대비 주가가 비싼가", "낮을수록 상대점수↑")
                        MetricGuideRow("④ 6개월 상승률", "최근 가격 흐름이 받쳐주나", "과열도 함께 확인")
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
                        Text("상위 조사 후보", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("4지표가 모두 있는 ${rankedStocks.size}종목만 순위 산출", fontSize = 11.sp, color = TextSecondaryLight)
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
                    Text("2,649종목 검색 · 필터 · 정렬")
                }
            }

            item {
                Text(
                    "종목 식별: KRX KIND · M01/M02: OpenDART · M03/M04: 네이버증권 공개 데이터",
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
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
private fun MetricGuideRow(title: String, meaning: String, direction: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(meaning, fontSize = 11.sp, color = TextSecondaryLight)
        }
        Text(direction, fontSize = 10.sp, color = BlueAccent)
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
                        if (stock.rankOrder != null) {
                            Surface(shape = RoundedCornerShape(7.dp), color = BlueAccent.copy(alpha = 0.12f)) {
                                Text(
                                    "${stock.rankOrder}위",
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    color = BlueAccent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(7.dp))
                        }
                        Text(stock.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stock.issuerId, fontSize = 11.sp, color = TextSecondaryLight)
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text("${stock.market} · ${stock.sector}", fontSize = 11.sp, color = TextSecondaryLight)
                }
                StatusBadge(status = stock.status)
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CompactMetric("매출", metricCompact(stock.m01RevGrowth.rawValue, "%", 1))
                CompactMetric("마진", metricCompact(stock.m02OpMargin.rawValue, "%", 1))
                CompactMetric("PER", metricCompact(stock.m03Per.rawValue, "배", 1))
                CompactMetric("6개월", metricCompact(stock.m04Price6m.rawValue, "%", 1))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (stock.isCompositeComplete) "4지표 종합 상대점수" else "실데이터 ${availableMetricCount(stock)}/4 · 순위 보류",
                    fontSize = 11.sp,
                    color = TextSecondaryLight
                )
                Text(
                    stock.compositeScore?.let { String.format("%.1f점", it) } ?: "미산출",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (stock.compositeScore != null) BlueAccent else TextSecondaryLight
                )
            }
        }
    }
}

private fun availableMetricCount(stock: StockSummary): Int = listOf(
    stock.m01RevGrowth, stock.m02OpMargin, stock.m03Per, stock.m04Price6m
).count { it.isAvailable }

private fun metricCompact(value: Double?, unit: String, decimals: Int): String {
    if (value == null) return "N/A"
    return "% .${decimals}f".format(value).trim() + unit
}

@Composable
private fun CompactMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = TextSecondaryLight)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

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
import com.krstock.v3.data.model.DataStatus
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
    val completeCount = stocks.count { it.isCompositeComplete }
    val demoCount = stocks.count { it.status == DataStatus.DEMO }
    val topStocks = stocks.filter { it.rankOrder != null }.take(8)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "KR4 국내주식 연구",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                        Text(
                            "4지표 후보 선별 · 장기 조사 우선순위",
                            fontSize = 11.sp,
                            color = TextSecondaryLight
                        )
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
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "DEMO 데이터 모드",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "현재 APK는 실시간 OpenDART/KRX 제공자가 연결되지 않았습니다. 내장 숫자는 화면·계산·예외처리 검증용이며 실제 투자판단에 사용하면 안 됩니다.",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = BlueAccent.copy(alpha = 0.08f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "조사할 가치가 높은 종목을 먼저 찾는 앱",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = BlueAccent
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "매출 증가율 · 영업이익률 · 실적 기준 PER · 최근 6개월 주가 상승률을 각각 25%로 평가합니다. 단기 급등 예측이 아니라 수개월~1·2년 관점에서 더 조사할 후보를 좁히는 목적입니다.",
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SummaryPill("등록", "${stocks.size}개", Modifier.weight(1f))
                            SummaryPill("4지표 완성", "${completeCount}개", Modifier.weight(1f))
                            SummaryPill("DEMO", "${demoCount}개", Modifier.weight(1f))
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("4지표를 이렇게 읽으면 됩니다", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        MetricGuideRow("① 매출 증가율", "회사가 실제로 커지는가", "높을수록 유리")
                        MetricGuideRow("② 영업이익률", "본업에서 이익을 남기는가", "높을수록 유리")
                        MetricGuideRow("③ 실적 PER", "버는 돈 대비 주가가 비싼가", "동종업계 대비 낮을수록 유리")
                        MetricGuideRow("④ 6개월 상승률", "시장 흐름이 뒷받침되는가", "과열 여부와 함께 확인")
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
                        Text("완성된 4지표 점수 기준 자동 정렬", fontSize = 11.sp, color = TextSecondaryLight)
                    }
                    TextButton(onClick = onNavigateToList) {
                        Text("전체보기 >")
                    }
                }
            }

            items(topStocks, key = { it.issuerId }) { stock ->
                StockSummaryCard(stock = stock, onClick = { onStockClick(stock.issuerId) })
            }

            item {
                OutlinedButton(
                    onClick = onNavigateToList,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("전체 종목 검색 · 필터 · 결측 확인")
                }
            }
        }
    }
}

@Composable
private fun SummaryPill(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    ) {
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
private fun MetricGuideRow(title: String, meaning: String, direction: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(meaning, fontSize = 12.sp, color = TextSecondaryLight)
        }
        Text(direction, fontSize = 11.sp, color = BlueAccent)
    }
}

@Composable
fun StockSummaryCard(stock: StockSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
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
                            Surface(
                                shape = RoundedCornerShape(7.dp),
                                color = BlueAccent.copy(alpha = 0.12f)
                            ) {
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                CompactMetric("매출", stock.m01RevGrowth.rawValue?.let { "${it}%" } ?: "N/A")
                CompactMetric("마진", stock.m02OpMargin.rawValue?.let { "${it}%" } ?: "N/A")
                CompactMetric("PER", stock.m03Per.rawValue?.let { "${it}배" } ?: "N/A")
                CompactMetric("6개월", stock.m04Price6m.rawValue?.let { "${it}%" } ?: "N/A")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (stock.isCompositeComplete) "4지표 종합 상대점수" else "결측치로 종합점수 보류",
                    fontSize = 12.sp,
                    color = TextSecondaryLight
                )
                Text(
                    text = stock.compositeScore?.let { String.format("%.1f점", it) } ?: "미산출",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (stock.compositeScore != null) BlueAccent else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun CompactMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = TextSecondaryLight)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

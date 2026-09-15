package com.krstock.v3.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.model.StockSummary
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.ui.components.StatusBadge
import com.krstock.v3.ui.theme.BlueAccent
import com.krstock.v3.ui.theme.BlueAccentSoft
import com.krstock.v3.ui.theme.BorderLight
import com.krstock.v3.ui.theme.SurfaceMuted
import com.krstock.v3.ui.theme.TextSecondaryLight
import com.krstock.v3.ui.theme.TextTertiaryLight

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
    val m01Count = remember(stocks) { stocks.count { it.m01RevGrowth.isAvailable } }
    val m02Count = remember(stocks) { stocks.count { it.m02OpMargin.isAvailable } }
    val m03Count = remember(stocks) { stocks.count { it.m03Per.isAvailable } }
    val m04Count = remember(stocks) { stocks.count { it.m04Price6m.isAvailable } }
    val perLossCount = remember(stocks) { stocks.count { it.isLossMaking } }
    val perUnresolvedCount = remember(stocks, m03Count, perLossCount) { (stocks.size - m03Count - perLossCount).coerceAtLeast(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column {
                        Text("KR4 국내주식", style = MaterialTheme.typography.titleLarge)
                        Text("성장 · 수익성 · 가치 · 흐름", style = MaterialTheme.typography.labelMedium, color = TextSecondaryLight)
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
            contentPadding = PaddingValues(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SnapshotHero(
                    total = stocks.size,
                    complete = StockRepository.completeCount(),
                    snapshotDate = StockRepository.quantSnapshotDate(),
                    priceDate = StockRepository.priceCutoffDate()
                )
            }

            item {
                SectionHeader(
                    title = "데이터 연결 상태",
                    subtitle = "숫자는 앱에 실제로 들어있는 현재 스냅샷 기준"
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCoverageTile("매출 증가율", m01Count, stocks.size, "성장", Modifier.weight(1f))
                    MetricCoverageTile("영업이익률", m02Count, stocks.size, "수익성", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCoverageTile("실적 PER", m03Count, stocks.size, "가치", Modifier.weight(1f), "적자 미적용 ${perLossCount} · 미연결 ${perUnresolvedCount}")
                    MetricCoverageTile("6개월 상승률", m04Count, stocks.size, "흐름", Modifier.weight(1f))
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(15.dp)) {
                        Text("4지표를 한눈에 읽는 법", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(10.dp))
                        GuideLine("매출", "작년보다 장사가 커졌나", "높을수록 유리")
                        GuideLine("마진", "본업에서 얼마나 남기나", "높을수록 유리")
                        GuideLine("PER", "실제 이익 대비 주가가 비싼가", "낮을수록 유리")
                        GuideLine("6개월", "최근 주가 흐름이 받쳐주나", "상승 흐름 확인")
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text("상위 조사 후보", style = MaterialTheme.typography.titleMedium)
                        Text("4지표 완성 ${rankedStocks.size}종목 기준", style = MaterialTheme.typography.labelMedium, color = TextSecondaryLight)
                    }
                    TextButton(onClick = onNavigateToList, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("전체보기")
                    }
                }
            }

            items(previewStocks, key = { "${it.market}:${it.issuerId}" }) { stock ->
                StockSummaryCard(stock = stock, onClick = { onStockClick(stock.issuerId) })
            }

            item {
                OutlinedButton(
                    onClick = onNavigateToList,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("2,649종목 검색 · 필터 · 조합순위")
                }
            }

            item {
                Text(
                    "종목: KRX KIND · 재무: OpenDART · PER/가격: 네이버증권 공개 데이터",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextTertiaryLight
                )
            }
        }
    }
}

@Composable
private fun SnapshotHero(total: Int, complete: Int, snapshotDate: String, priceDate: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BlueAccentSoft),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, BlueAccent.copy(alpha = 0.12f))
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("실데이터 4지표 연결 완료", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = BlueAccent)
                    Spacer(Modifier.height(4.dp))
                    Text("추정치 대신 확인 가능한 실데이터만 사용", style = MaterialTheme.typography.bodyMedium, color = TextSecondaryLight)
                }
                Surface(color = BlueAccent, shape = RoundedCornerShape(10.dp)) {
                    Text("KR4", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroStat("전체", total.toString(), Modifier.weight(1f))
                HeroStat("4지표 완성", complete.toString(), Modifier.weight(1f))
                HeroStat("완성률", percentText(complete, total), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = BlueAccent.copy(alpha = 0.12f))
            Spacer(Modifier.height(10.dp))
            Text("재무 $snapshotDate  ·  주가 $priceDate", style = MaterialTheme.typography.labelMedium, color = TextSecondaryLight)
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondaryLight)
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = TextSecondaryLight)
    }
}

@Composable
private fun MetricCoverageTile(label: String, count: Int, total: Int, category: String, modifier: Modifier = Modifier, detail: String? = null) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(category, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = BlueAccent)
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Text("${count}개", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(detail ?: "${percentText(count, total)} 연결", fontSize = 10.sp, color = TextSecondaryLight, maxLines = 1)
        }
    }
}

@Composable
private fun GuideLine(label: String, meaning: String, direction: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = RoundedCornerShape(8.dp), color = SurfaceMuted) {
            Text(label, modifier = Modifier.width(54.dp).padding(vertical = 6.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = BlueAccent, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Spacer(Modifier.width(10.dp))
        Text(meaning, modifier = Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(direction, fontSize = 10.sp, color = TextSecondaryLight)
    }
}

@Composable
fun StockSummaryCard(
    stock: StockSummary,
    onClick: () -> Unit,
    displayRank: Int? = stock.rankOrder,
    displayScore: Double? = stock.compositeScore,
    scoreLabel: String = "4지표 종합 상대점수",
    missingLabel: String = "실데이터 ${availableMetricCount(stock)}/4 · 순위 보류"
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stock_${stock.issuerId}")
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (displayRank != null) {
                            Surface(shape = RoundedCornerShape(8.dp), color = BlueAccent.copy(alpha = 0.10f)) {
                                Text("${displayRank}위", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = BlueAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(7.dp))
                        }
                        Text(stock.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${stock.issuerId}  ·  ${stock.market}  ·  ${stock.sector}", fontSize = 10.sp, color = TextSecondaryLight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                StatusBadge(status = stock.status)
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("매출 증가율", metricCompact(stock.m01RevGrowth.rawValue, "%", 1), stock.m01RevGrowth.isAvailable, metricStatus(stock.m01RevGrowth.reason, stock.m01RevGrowth.isAvailable), Modifier.weight(1f))
                MetricTile("영업이익률", metricCompact(stock.m02OpMargin.rawValue, "%", 1), stock.m02OpMargin.isAvailable, metricStatus(stock.m02OpMargin.reason, stock.m02OpMargin.isAvailable), Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("실적 PER", metricCompact(stock.m03Per.rawValue, "배", 1), stock.m03Per.isAvailable, perStatus(stock.isLossMaking, stock.m03Per.isAvailable), Modifier.weight(1f))
                MetricTile("6개월 상승률", metricCompact(stock.m04Price6m.rawValue, "%", 1), stock.m04Price6m.isAvailable, metricStatus(stock.m04Price6m.reason, stock.m04Price6m.isAvailable), Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(11.dp),
                color = if (displayScore != null) BlueAccent.copy(alpha = 0.07f) else SurfaceMuted
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (displayScore != null) scoreLabel else missingLabel, fontSize = 10.sp, color = TextSecondaryLight, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        displayScore?.let { String.format("%.1f점", it) } ?: "미산출",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = if (displayScore != null) BlueAccent else TextSecondaryLight
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, available: Boolean, note: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(11.dp),
        color = SurfaceMuted,
        border = BorderStroke(1.dp, BorderLight)
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 10.dp)) {
            Text(label, fontSize = 10.sp, color = TextSecondaryLight, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (available) MaterialTheme.colorScheme.onSurface else TextTertiaryLight)
            Spacer(Modifier.height(2.dp))
            Text(note, fontSize = 9.sp, color = if (available) TextSecondaryLight else TextTertiaryLight, maxLines = 1)
        }
    }
}

private fun metricStatus(reason: String?, available: Boolean): String =
    if (available) "연결됨" else if (reason.isNullOrBlank()) "자료 확인 필요" else "자료 확인 필요"

private fun perStatus(isLossMaking: Boolean, available: Boolean): String {
    if (available) return "연결됨"
    return if (isLossMaking) "적자 · PER 미적용" else "자료 확인 필요"
}

private fun availableMetricCount(stock: StockSummary): Int = listOf(
    stock.m01RevGrowth, stock.m02OpMargin, stock.m03Per, stock.m04Price6m
).count { it.isAvailable }

private fun metricCompact(value: Double?, unit: String, decimals: Int): String {
    if (value == null) return "N/A"
    return "% .${decimals}f".format(value).trim() + unit
}

private fun percentText(value: Int, total: Int): String {
    if (total <= 0) return "0%"
    return String.format("%.1f%%", value * 100.0 / total)
}

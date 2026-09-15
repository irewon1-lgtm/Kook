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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.model.MetricValue
import com.krstock.v3.data.model.StockSummary
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.ui.components.StatusBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStockClick: (String) -> Unit,
    onNavigateToList: () -> Unit
) {
    val stocks = remember { StockRepository.getAllStocks() }
    val rankedStocks = remember(stocks) { stocks.filter { it.rankOrder != null }.sortedBy { it.rankOrder } }
    val previewStocks = remember(stocks, rankedStocks) {
        if (rankedStocks.isNotEmpty()) rankedStocks.take(5)
        else {
            val preferred = listOf("005930", "000660", "035420", "005380", "000250")
            preferred.mapNotNull { code -> stocks.find { it.issuerId == code } }.take(5)
        }
    }
    val m01Count = remember(stocks) { stocks.count { it.m01RevGrowth.isAvailable } }
    val m02Count = remember(stocks) { stocks.count { it.m02OpMargin.isAvailable } }
    val m03Count = remember(stocks) { stocks.count { it.m03Per.isAvailable } }
    val m04Count = remember(stocks) { stocks.count { it.m04Price6m.isAvailable } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = {
                    Column {
                        Text("KR4 국내주식", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Text(
                            "성장 · 수익성 · 가치 · 흐름",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                .padding(horizontal = 14.dp)
                .testTag("home_list"),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                PrimaryStockExplorer(
                    total = stocks.size,
                    rankedCount = rankedStocks.size,
                    onClick = onNavigateToList
                )
            }

            item {
                SnapshotCompactCard(
                    total = stocks.size,
                    complete = StockRepository.completeCount(),
                    snapshotDate = StockRepository.quantSnapshotDate(),
                    priceDate = StockRepository.priceCutoffDate()
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("상위 조사 후보", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "성장 · 수익성 · 가치 · 흐름을 한눈에 비교",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            "TOP ${previewStocks.size}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            items(previewStocks, key = { "${it.market}:${it.issuerId}" }) { stock ->
                StockSummaryCard(stock = stock, onClick = { onStockClick(stock.issuerId) })
            }

            item {
                DataCoverageCompact(
                    total = stocks.size,
                    m01 = m01Count,
                    m02 = m02Count,
                    m03 = m03Count,
                    m04 = m04Count
                )
            }

            item { FinanceGuideCompact() }

            item {
                Text(
                    "종목 KRX KIND · 재무 OpenDART · PER/가격 네이버증권 공개 데이터",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PrimaryStockExplorer(total: Int, rankedCount: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 116.dp)
            .testTag("primary_stock_explorer")
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = scheme.primary,
            contentColor = scheme.onPrimary
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = scheme.onPrimary.copy(alpha = 0.12f),
                    contentColor = scheme.onPrimary
                ) {
                    Text(
                        "4축 스크리닝",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "상위 조사 후보 전체보기",
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "${total}종목 검색 · 필터 · 조합순위  |  현재 순위 ${rankedCount}종목",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = scheme.onPrimary.copy(alpha = 0.88f)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("›", fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SnapshotCompactCard(total: Int, complete: Int, snapshotDate: String, priceDate: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("실데이터 4지표 연결 완료", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text(
                    "4지표 완성 $complete/$total · 재무 $snapshotDate · 주가 $priceDate",
                    fontSize = 10.sp,
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
                    percentText(complete, total),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun DataCoverageCompact(total: Int, m01: Int, m02: Int, m03: Int, m04: Int) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Column(Modifier.padding(13.dp)) {
            Text("데이터 커버리지", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            CoverageRow("매출 증가율", m01, total)
            CoverageRow("영업이익률", m02, total)
            CoverageRow("실적 PER", m03, total)
            CoverageRow("6개월 상승률", m04, total)
        }
    }
}

@Composable
private fun CoverageRow(label: String, count: Int, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${count}개 · ${percentText(count, total)}", fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FinanceGuideCompact() {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Column(Modifier.padding(13.dp)) {
            Text("4지표 해석 프레임", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "성장=매출 · 수익성=영업이익률 · 가치=실적 PER · 흐름=6개월 주가",
                fontSize = 10.sp,
                lineHeight = 15.sp,
                color = scheme.onSurfaceVariant
            )
        }
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
    val scheme = MaterialTheme.colorScheme
    val isTopRank = displayRank != null && displayRank <= 3
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stock_${stock.issuerId}")
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isTopRank) 2.dp else 1.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(
            1.dp,
            if (isTopRank) scheme.primary.copy(alpha = 0.36f) else scheme.outlineVariant
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (displayRank != null) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isTopRank) scheme.primary else scheme.primaryContainer,
                                contentColor = if (isTopRank) scheme.onPrimary else scheme.onPrimaryContainer
                            ) {
                                Text(
                                    "${displayRank}위",
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(
                            stock.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            lineHeight = 27.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(7.dp))
                    Text(
                        "${stock.issuerId}  ·  ${stock.market}  ·  ${stock.sector}",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusBadge(status = stock.status)
            }

            Spacer(modifier = Modifier.height(15.dp))
            HorizontalDivider(color = scheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("한눈 비교", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("시장 내 상대위치", fontSize = 11.sp, color = scheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(9.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("comparison_${stock.issuerId}")
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile(stock.m01RevGrowth, "성장", "매출 증가율", Modifier.weight(1f))
                    MetricTile(stock.m02OpMargin, "수익성", "영업이익률", Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile(stock.m03Per, "가치", "실적 PER", Modifier.weight(1f), lossMaking = stock.isLossMaking)
                    MetricTile(stock.m04Price6m, "흐름", "6개월 상승률", Modifier.weight(1f))
                }
            }

            Spacer(modifier = Modifier.height(13.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = if (displayScore != null) scheme.primaryContainer else scheme.surfaceVariant,
                contentColor = if (displayScore != null) scheme.onPrimaryContainer else scheme.onSurfaceVariant
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "종합 비교",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = LocalContentColor.current.copy(alpha = 0.72f)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (displayScore != null) scoreLabel else missingLabel,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (displayScore != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "전체시장 기준 상대 위치",
                                fontSize = 11.sp,
                                color = LocalContentColor.current.copy(alpha = 0.76f)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        displayScore?.let { String.format("%.1f점", it) } ?: "미산출",
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    metric: MetricValue,
    axis: String,
    label: String,
    modifier: Modifier = Modifier,
    lossMaking: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val available = metric.isAvailable && metric.rawValue != null
    val value = when {
        !available -> "N/A"
        metric.id == "M03" -> String.format("%.1f배", metric.rawValue)
        else -> String.format("%.1f%%", metric.rawValue)
    }
    val note = when {
        available -> marketPosition(metric.percentileScore)
        lossMaking && metric.id == "M03" -> "적자 · PER 미적용"
        else -> "자료 확인 필요"
    }

    Surface(
        modifier = modifier.heightIn(min = 110.dp),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceVariant,
        contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer
                ) {
                    Text(
                        axis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                value,
                fontSize = 21.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.Bold,
                color = if (available) scheme.onSurface else scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                note,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (available) scheme.primary else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (available && metric.percentileScore != null) {
                Spacer(Modifier.height(8.dp))
                RelativeBar(metric.percentileScore)
            }
        }
    }
}

@Composable
private fun RelativeBar(score: Double) {
    val scheme = MaterialTheme.colorScheme
    val fraction = (score / 100.0).coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp))
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = scheme.outlineVariant) {}
        Surface(
            modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight(),
            color = scheme.primary
        ) {}
    }
}

private fun marketPosition(score: Double?): String = when {
    score == null -> "상대점수 없음"
    score >= 90.0 -> "시장 상위 10%"
    score >= 75.0 -> "시장 상위 25%"
    score >= 50.0 -> "시장 중상위"
    score >= 25.0 -> "시장 중하위"
    else -> "시장 하위"
}

private fun availableMetricCount(stock: StockSummary): Int = listOf(
    stock.m01RevGrowth,
    stock.m02OpMargin,
    stock.m03Per,
    stock.m04Price6m
).count { it.isAvailable }

private fun percentText(value: Int, total: Int): String {
    if (total <= 0) return "0%"
    return String.format("%.1f%%", value * 100.0 / total)
}

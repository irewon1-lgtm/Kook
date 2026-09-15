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
import androidx.compose.ui.text.style.TextAlign
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
    val perUnresolvedCount = remember(stocks, m03Count, perLossCount) {
        (stocks.size - m03Count - perLossCount).coerceAtLeast(0)
    }

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
                        Text("KR4 국내주식", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "성장 · 수익성 · 가치 · 흐름",
                            style = MaterialTheme.typography.labelMedium,
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
            contentPadding = PaddingValues(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                    title = "데이터 커버리지",
                    subtitle = "현재 앱에 검증되어 들어온 실제 값 기준"
                )
                Spacer(Modifier.height(9.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCoverageTile("매출 증가율", m01Count, stocks.size, "GROWTH", Modifier.weight(1f))
                    MetricCoverageTile("영업이익률", m02Count, stocks.size, "PROFIT", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCoverageTile(
                        "실적 PER",
                        m03Count,
                        stocks.size,
                        "VALUE",
                        Modifier.weight(1f),
                        "적자 ${perLossCount} · 미연결 ${perUnresolvedCount}"
                    )
                    MetricCoverageTile("6개월 상승률", m04Count, stocks.size, "MOMENTUM", Modifier.weight(1f))
                }
            }

            item {
                FinanceGuideCard()
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("상위 조사 후보", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "4지표 완성 ${rankedStocks.size}종목 · 시장 상대점수 순",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onNavigateToList, contentPadding = PaddingValues(horizontal = 8.dp)) {
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
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("${stocks.size}종목 검색 · 필터 · 조합순위")
                }
            }

            item {
                Text(
                    "종목 KRX KIND · 재무 OpenDART · PER/가격 네이버증권 공개 데이터",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SnapshotHero(total: Int, complete: Int, snapshotDate: String, priceDate: String) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer
        ),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.22f))
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text("실데이터 4지표 연결 완료", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "추정값 대신 확인 가능한 원천 데이터만 사용",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.78f)
                    )
                }
                Surface(
                    color = scheme.primary,
                    contentColor = scheme.onPrimary,
                    shape = RoundedCornerShape(9.dp)
                ) {
                    Text(
                        "KR4",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroStat("전체", total.toString(), Modifier.weight(1f))
                HeroStat("4지표 완성", complete.toString(), Modifier.weight(1f))
                HeroStat("완성률", percentText(complete, total), Modifier.weight(1f))
            }
            Spacer(Modifier.height(13.dp))
            HorizontalDivider(color = scheme.onPrimaryContainer.copy(alpha = 0.14f))
            Spacer(Modifier.height(10.dp))
            Text(
                "재무 $snapshotDate  ·  주가 $priceDate",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onPrimaryContainer.copy(alpha = 0.74f)
            )
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.72f))
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(1.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MetricCoverageTile(
    label: String,
    count: Int,
    total: Int,
    category: String,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(category, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
            Spacer(Modifier.height(5.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(9.dp))
            Text("${count}개", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(1.dp))
            Text(
                detail ?: "${percentText(count, total)} 연결",
                fontSize = 9.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FinanceGuideCard() {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Column(Modifier.padding(15.dp)) {
            Text("4지표 해석 프레임", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(
                "숫자 자체보다 성장·수익성·가치·가격 흐름의 균형을 먼저 봅니다.",
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            GuideLine("성장", "매출이 커지는가", "높을수록 유리")
            GuideLine("수익", "본업에서 남기는가", "높을수록 유리")
            GuideLine("가치", "이익 대비 가격이 비싼가", "PER 낮을수록 유리")
            GuideLine("흐름", "가격 추세가 받쳐주는가", "과열 여부 별도 확인")
        }
    }
}

@Composable
private fun GuideLine(label: String, meaning: String, direction: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = RoundedCornerShape(7.dp), color = scheme.surfaceVariant) {
            Text(
                label,
                modifier = Modifier.width(50.dp).padding(vertical = 6.dp),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            meaning,
            modifier = Modifier.weight(1f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        Text(direction, fontSize = 9.sp, color = scheme.onSurfaceVariant, maxLines = 1)
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stock_${stock.issuerId}")
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant),
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
                            Surface(
                                shape = RoundedCornerShape(7.dp),
                                color = scheme.primaryContainer,
                                contentColor = scheme.onPrimaryContainer
                            ) {
                                Text(
                                    "${displayRank}위",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            stock.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        "${stock.issuerId}  ·  ${stock.market}  ·  ${stock.sector}",
                        fontSize = 10.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusBadge(status = stock.status)
            }

            Spacer(modifier = Modifier.height(13.dp))
            HorizontalDivider(color = scheme.outlineVariant)
            Spacer(modifier = Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(stock.m01RevGrowth, "매출 증가율", Modifier.weight(1f))
                MetricTile(stock.m02OpMargin, "영업이익률", Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(stock.m03Per, "실적 PER", Modifier.weight(1f), lossMaking = stock.isLossMaking)
                MetricTile(stock.m04Price6m, "6개월 상승률", Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(11.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = if (displayScore != null) scheme.primaryContainer else scheme.surfaceVariant,
                contentColor = if (displayScore != null) scheme.onPrimaryContainer else scheme.onSurfaceVariant
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (displayScore != null) scoreLabel else missingLabel,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (displayScore != null) {
                            Text("전체시장 상대위치 · 업종 비교는 다음 단계 적용", fontSize = 8.sp, color = LocalContentColor.current.copy(alpha = 0.72f))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        displayScore?.let { String.format("%.1f점", it) } ?: "미산출",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    metric: MetricValue,
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
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceVariant,
        contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 10.dp)) {
            Text(label, fontSize = 9.sp, color = scheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (available) scheme.onSurface else scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                note,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = if (available) scheme.primary else scheme.onSurfaceVariant,
                maxLines = 1
            )
            if (available && metric.percentileScore != null) {
                Spacer(Modifier.height(6.dp))
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
            .height(4.dp)
            .clip(RoundedCornerShape(999.dp))
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = scheme.outlineVariant
        ) {}
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

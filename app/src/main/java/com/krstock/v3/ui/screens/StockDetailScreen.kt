package com.krstock.v3.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.model.MetricValue
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.ui.components.StatusBadge
import com.krstock.v3.ui.theme.BlueAccent
import com.krstock.v3.ui.theme.TextSecondaryLight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    issuerId: String,
    onBack: () -> Unit
) {
    val stockDetail = remember(issuerId) { StockRepository.getStockDetail(issuerId) }

    if (stockDetail == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("종목을 찾을 수 없음", fontWeight = FontWeight.Bold) },
                    navigationIcon = { TextButton(onClick = onBack) { Text("< 뒤로") } }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("요청한 종목코드 '$issuerId'가 현재 데이터셋에 없습니다.", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "이전처럼 다른 종목(예: 삼성전자)으로 몰래 대체하지 않고 NOT FOUND로 처리합니다.",
                    fontSize = 13.sp,
                    color = TextSecondaryLight
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onBack) { Text("목록으로 돌아가기") }
            }
        }
        return
    }

    val summary = stockDetail.summary
    val report = stockDetail.report

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("${summary.name} (${summary.issuerId})", fontWeight = FontWeight.Bold)
                        Text("${summary.market} · ${summary.sector}", fontSize = 11.sp, color = TextSecondaryLight)
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("< 뒤로") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("DEMO 데이터", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        "실시간 투자정보가 아닙니다. 아래 수치는 앱의 계산·화면·예외처리를 검증하기 위한 내장 샘플입니다.",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("조사 우선순위", fontSize = 11.sp, color = TextSecondaryLight)
                            Text(
                                summary.rankOrder?.let { "${it}위" } ?: "미산출",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = BlueAccent
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            StatusBadge(status = summary.status)
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                summary.compositeScore?.let { String.format("종합 %.1f점", it) } ?: "종합점수 없음",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(report.oneLineView, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(report.quantSummary, fontSize = 12.sp, color = TextSecondaryLight)
                }
            }

            Text("기본 4대 정량지표", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(
                "각 지표의 원값과 상대점수를 함께 봅니다. 상대점수는 같은 비교집단 안에서의 위치이며 절대적인 매수 확률이 아닙니다.",
                fontSize = 12.sp,
                color = TextSecondaryLight
            )

            MetricCard(summary.m01RevGrowth)
            MetricCard(summary.m02OpMargin)
            MetricCard(summary.m03Per)
            MetricCard(summary.m04Price6m)

            Text("정량 해석", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            AnalysisCard {
                InfoSection("본업의 질", report.businessQuality)
                InfoSection("밸류에이션", report.valuationView)
                InfoSection("주가 흐름", report.momentumView)
            }

            Text("기업 분석 보고서", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            AnalysisCard {
                InfoSection("최근 변화", report.recentChanges)
                InfoSection("변화 이유", report.changeReason)
                BulletSection("긍정 요인", report.positiveFactors)
                BulletSection("위험 요인", report.riskFactors)
                InfoSection("반대 근거", report.counterArguments)
                BulletSection("다음 확인 조건", report.nextVerificationConditions)
            }

            Text("뉴스·공시", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            if (stockDetail.news.isEmpty() && stockDetail.filings.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("실데이터 제공자 미연결", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(5.dp))
                        Text(
                            "가짜 뉴스·가짜 DART 링크를 보여주지 않습니다. 실제 제공자가 연결되기 전에는 빈 상태로 표시하는 것이 정상입니다.",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = TextSecondaryLight
                        )
                    }
                }
            } else {
                stockDetail.news.take(3).forEach { news ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(news.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("${news.source} | ${news.publishedAt}", fontSize = 11.sp, color = TextSecondaryLight)
                        }
                    }
                }
                stockDetail.filings.take(2).forEach { filing ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(filing.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("DART | ${filing.publishedAt}", fontSize = 11.sp, color = TextSecondaryLight)
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BlueAccent.copy(alpha = 0.07f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("데이터 한계", fontWeight = FontWeight.Bold, color = BlueAccent)
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(report.dataLimitations, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(modifier = Modifier.height(5.dp))
                    Text("표시 출처: ${summary.dataSource} · 기준일: ${summary.asOfDate}", fontSize = 11.sp, color = TextSecondaryLight)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MetricCard(metric: MetricValue) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(metric.nameKo, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(metric.description, fontSize = 12.sp, lineHeight = 18.sp, color = TextSecondaryLight)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        metric.rawValue?.let { "${it}${metric.unit}" } ?: "N/A",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        metric.percentileScore?.let { "상대 ${String.format("%.0f점", it)}" } ?: "점수 없음",
                        fontSize = 11.sp,
                        color = BlueAccent
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (!metric.isAvailable) {
                Text(metric.reason ?: "자료 부족", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            } else {
                Text("해석: ${metric.interpretation}", fontSize = 12.sp, lineHeight = 18.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("주의: ${metric.caution}", fontSize = 11.sp, lineHeight = 17.sp, color = TextSecondaryLight)
        }
    }
}

@Composable
private fun AnalysisCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun InfoSection(title: String, content: String) {
    Column(modifier = Modifier.padding(bottom = 13.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = BlueAccent)
        Spacer(modifier = Modifier.height(3.dp))
        Text(content, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun BulletSection(title: String, items: List<String>) {
    Column(modifier = Modifier.padding(bottom = 13.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = BlueAccent)
        Spacer(modifier = Modifier.height(3.dp))
        items.forEach { item ->
            Text("• $item", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

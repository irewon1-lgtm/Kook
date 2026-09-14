package com.krstock.v3.ui.screens

import androidx.compose.foundation.background
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
    val stockDetail = remember(issuerId) { StockRepository.getStockDetail(issuerId) } ?: return
    val summary = stockDetail.summary
    val report = stockDetail.report

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${summary.name} (${summary.issuerId})", fontWeight = FontWeight.Bold) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Info
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("시장: ${summary.market}", fontSize = 13.sp, color = TextSecondaryLight)
                        StatusBadge(status = summary.status)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (summary.isCompositeComplete) "우선 조사 후보 순위 점수: ${String.format("%.2f점", summary.compositeScore)}" else "종합점수 미완성 (결측치 존재)",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = BlueAccent
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("기준일: ${summary.asOfDate} | 출처: ${summary.dataSource}", fontSize = 11.sp, color = TextSecondaryLight)
                }
            }

            // 4 Core Metrics Detail
            Text("기본 4대 정량지표 및 상대점수 (각 25%)", fontWeight = FontWeight.Bold, fontSize = 16.sp)

            MetricRow(summary.m01RevGrowth)
            MetricRow(summary.m02OpMargin)
            MetricRow(summary.m03Per)
            MetricRow(summary.m04Price6m)

            // Analysis Report Section
            Text("기업 분석 보고서", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ReportSection("최근 변화", report.recentChanges)
                    ReportSection("변화 이유", report.changeReason)
                    ReportSection("긍정 요인", report.positiveFactors.joinToString("\n• ", prefix = "• "))
                    ReportSection("위험 요인", report.riskFactors.joinToString("\n• ", prefix = "• "))
                    ReportSection("반대 근거", report.counterArguments)
                    ReportSection("다음 확인 조건", report.nextVerificationConditions.joinToString("\n• ", prefix = "• "))
                }
            }

            // News Section (Max 3)
            Text("관련 뉴스 (최대 3개)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            stockDetail.news.take(3).forEach { news ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(news.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${news.source} | ${news.publishedAt}", fontSize = 11.sp, color = TextSecondaryLight)
                    }
                }
            }

            // Filings Section (Max 2)
            Text("DART 공시 (최대 2개)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            stockDetail.filings.take(2).forEach { filing ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(filing.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("DART 공식 공시 | 접수일: ${filing.publishedAt}", fontSize = 11.sp, color = TextSecondaryLight)
                    }
                }
            }
        }
    }
}

@Composable
fun MetricRow(metric: MetricValue) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(metric.nameKo, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (!metric.isAvailable) {
                    Text(metric.reason ?: "사유 미기록", fontSize = 11.sp, color = Color.Red)
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = metric.rawValue?.let { "${it}${metric.unit}" } ?: "N/A",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = metric.percentileScore?.let { "상대점수: ${String.format("%.1f점", it)}" } ?: "점수 없음",
                    fontSize = 11.sp,
                    color = TextSecondaryLight
                )
            }
        }
    }
}

@Composable
fun ReportSection(title: String, content: String) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = BlueAccent)
        Spacer(modifier = Modifier.height(2.dp))
        Text(content, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

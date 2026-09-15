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
fun StockDetailScreen(issuerId: String, onBack: () -> Unit) {
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
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("요청한 종목코드 '$issuerId'가 KOSPI·KOSDAQ 마스터에 없습니다.", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("없는 종목을 삼성전자 등 다른 회사로 대체하지 않습니다.", fontSize = 13.sp, color = TextSecondaryLight)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onBack) { Text("목록으로 돌아가기") }
            }
        }
        return
    }

    val summary = stockDetail.summary
    val report = stockDetail.report
    val availableCount = listOf(summary.m01RevGrowth, summary.m02OpMargin, summary.m03Per, summary.m04Price6m)
        .count { it.isAvailable }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("${summary.name} (${summary.issuerId})", fontWeight = FontWeight.Bold)
                        Text("${summary.market} · ${summary.sector}", fontSize = 11.sp, color = TextSecondaryLight)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("< 뒤로") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BlueAccent.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("실데이터 $availableCount/4", fontWeight = FontWeight.Bold, color = BlueAccent)
                            Text(
                                if (summary.isCompositeComplete) "4지표 완성 · 순위 산출 가능" else "결측 지표는 추정하지 않고 보류",
                                fontSize = 12.sp,
                                color = TextSecondaryLight
                            )
                        }
                        StatusBadge(status = summary.status)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    IdentityRow("시장", summary.market)
                    IdentityRow("업종", summary.sector)
                    IdentityRow("상장일", summary.listingDate.ifBlank { "확인 필요" })
                    IdentityRow("재무 스냅샷", StockRepository.quantSnapshotDate())
                    IdentityRow("주가 기준일", StockRepository.priceCutoffDate())
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("조사 우선순위", fontSize = 11.sp, color = TextSecondaryLight)
                            Text(summary.rankOrder?.let { "${it}위" } ?: "보류", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = BlueAccent)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("종합 상대점수", fontSize = 11.sp, color = TextSecondaryLight)
                            Text(
                                summary.compositeScore?.let { String.format("%.1f점", it) } ?: "미산출",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(report.oneLineView, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(report.quantSummary, fontSize = 12.sp, lineHeight = 18.sp, color = TextSecondaryLight)
                }
            }

            Text("4대 정량지표", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(
                "원값 + 전체 비교군 상대점수 + 실제 원천과 계산기준을 함께 표시합니다.",
                fontSize = 12.sp,
                color = TextSecondaryLight
            )
            MetricCard(summary.m01RevGrowth)
            MetricCard(summary.m02OpMargin)
            MetricCard(summary.m03Per)
            MetricCard(summary.m04Price6m)

            Text("정량 해석", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            AnalysisCard {
                InfoSection("사업 성장·수익성", report.businessQuality)
                InfoSection("밸류에이션", report.valuationView)
                InfoSection("가격 흐름", report.momentumView)
            }

            Text("조사 체크포인트", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            AnalysisCard {
                BulletSection("우호 요인", report.positiveFactors)
                BulletSection("위험·결측", report.riskFactors)
                InfoSection("반대 근거", report.counterArguments)
                BulletSection("다음 확인 조건", report.nextVerificationConditions)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BlueAccent.copy(alpha = 0.07f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("데이터 한계와 출처", fontWeight = FontWeight.Bold, color = BlueAccent)
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(report.dataLimitations, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(modifier = Modifier.height(7.dp))
                    Text("종목: KRX KIND", fontSize = 11.sp, color = TextSecondaryLight)
                    Text("재무: OpenDART ${StockRepository.dartFileName()}", fontSize = 11.sp, color = TextSecondaryLight)
                    Text("가격/EPS: 네이버증권 · 마감 ${StockRepository.priceCutoffDate()}", fontSize = 11.sp, color = TextSecondaryLight)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun IdentityRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = TextSecondaryLight)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MetricCard(metric: MetricValue) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
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
                Spacer(modifier = Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        metric.rawValue?.let {
                            if (metric.id == "M03") String.format("%.2f배", it) else String.format("%.1f%%", it)
                        } ?: "N/A",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        metric.percentileScore?.let { "상대 ${String.format("%.1f", it)}점" } ?: "점수 보류",
                        fontSize = 11.sp,
                        color = if (metric.percentileScore != null) BlueAccent else TextSecondaryLight
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (metric.isAvailable) {
                Text("해석 기준: ${metric.interpretation}", fontSize = 12.sp, lineHeight = 18.sp)
            } else {
                Text(metric.reason ?: "검증 가능한 값 없음", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (metric.basis.isNotBlank()) Text("계산 기준: ${metric.basis}", fontSize = 10.sp, color = TextSecondaryLight)
            if (metric.source.isNotBlank()) Text("출처: ${metric.source}", fontSize = 10.sp, color = TextSecondaryLight)
            if (metric.asOfDate.isNotBlank()) Text("기준일: ${metric.asOfDate}", fontSize = 10.sp, color = TextSecondaryLight)
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
        Text(content, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun BulletSection(title: String, items: List<String>) {
    Column(modifier = Modifier.padding(bottom = 13.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = BlueAccent)
        Spacer(modifier = Modifier.height(3.dp))
        items.forEach { item -> Text("• $item", fontSize = 13.sp, lineHeight = 20.sp) }
    }
}

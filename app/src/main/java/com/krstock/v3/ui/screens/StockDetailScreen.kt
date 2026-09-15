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
                Text("요청한 종목코드 '$issuerId'가 KOSPI·KOSDAQ 등록 마스터에 없습니다.", fontWeight = FontWeight.Bold)
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
                            Text("${summary.market} 실종목 등록", fontWeight = FontWeight.Bold, color = BlueAccent)
                            Text("종목 식별정보만 먼저 등록한 단계", fontSize = 12.sp, color = TextSecondaryLight)
                        }
                        StatusBadge(status = summary.status)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    IdentityRow("시장", summary.market)
                    IdentityRow("회사명", summary.name)
                    IdentityRow("종목코드", summary.issuerId)
                    IdentityRow("업종", summary.sector)
                    IdentityRow("상장일", summary.listingDate.ifBlank { "확인 필요" })
                    IdentityRow("마스터 기준일", summary.asOfDate)
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(report.oneLineView, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(report.quantSummary, fontSize = 12.sp, color = TextSecondaryLight)
                }
            }

            Text("4대 정량지표 · 아직 미수집", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(
                "종목 등록과 지표 수집을 분리했습니다. 검증된 실데이터가 연결되기 전에는 값·점수·순위를 만들지 않습니다.",
                fontSize = 12.sp,
                color = TextSecondaryLight
            )
            MetricCard(summary.m01RevGrowth)
            MetricCard(summary.m02OpMargin)
            MetricCard(summary.m03Per)
            MetricCard(summary.m04Price6m)

            Text("다음 연결 순서", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            AnalysisCard {
                BulletSection("실데이터 연결 조건", report.nextVerificationConditions)
                InfoSection("현재 판단 제한", report.counterArguments)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BlueAccent.copy(alpha = 0.07f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("데이터 상태", fontWeight = FontWeight.Bold, color = BlueAccent)
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(report.dataLimitations, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(modifier = Modifier.height(5.dp))
                    Text("출처: ${summary.dataSource}", fontSize = 11.sp, color = TextSecondaryLight)
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
                Text("미수집", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(metric.reason ?: "자료 미수집", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(4.dp))
            Text("해석 기준: ${metric.interpretation}", fontSize = 11.sp, lineHeight = 17.sp, color = TextSecondaryLight)
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

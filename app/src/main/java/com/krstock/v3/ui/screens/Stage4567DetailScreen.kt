package com.krstock.v3.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.krstock.v3.data.candidate.FinalCandidateRepository
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.stage.Stage4567Policy
import com.krstock.v3.data.stage.Stage4567SnapshotRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Stage4567DetailScreen(
    issuerId: String,
    onBack: () -> Unit,
    onOpenFullDetail: () -> Unit,
) {
    val stock = remember(issuerId) { StockRepository.getAllStocks().find { it.issuerId == issuerId } }
    val safety = remember(issuerId) {
        Stage4567SnapshotRepository.current(StockRepository.quantSnapshotDate())[issuerId]
    }
    val candidate = remember(issuerId) {
        FinalCandidateRepository.getFinalCandidates(100).find { it.issuerId == issuerId }
    }
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ 뒤로") } },
                title = {
                    Column {
                        Text(stock?.name ?: issuerId, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Stage4 → Stage7 검증 상세", fontSize = 10.sp, color = scheme.onSurfaceVariant)
                    }
                },
            )
        }
    ) { padding ->
        if (stock == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("종목을 찾을 수 없습니다.")
            }
            return@Scaffold
        }

        val band = Stage4567Policy.valuationBand(stock.m03Per.rawValue, stock.m03Per.percentileScore)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("stage4567_detail"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer),
                border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.22f)),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("검증 상태", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        candidate?.let { "FINAL ${it.candidateRank}위 · 기존 종합 ${it.sourceRank}위" }
                            ?: "최종 조사후보 아님 · 기존 종합 ${stock.rankOrder?.let { "${it}위" } ?: "보류"}",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("재무 ${StockRepository.quantSnapshotDate()} · 주가 ${StockRepository.priceCutoffDate()}", fontSize = 10.sp, color = scheme.onSurfaceVariant)
                }
            }

            DetailStageCard("STAGE 4 · 재무안정성") {
                if (safety == null) {
                    Text("검증 가능한 Stage4 스냅샷이 없어 미확인 처리합니다.", fontSize = 12.sp)
                } else {
                    val status = Stage4567Policy.safetyLabelKo(safety.status)
                    Text("판정: $status", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(Stage4567Policy.safetyReasonKo(safety.reason), fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    StageDetailMetric("부채 / 자본", safety.debtToEquityPct?.let { String.format("%.1f%%", it) } ?: "-")
                    StageDetailMetric("유동비율", safety.currentRatioPct?.let { String.format("%.1f%%", it) } ?: "-")
                    StageDetailMetric("자산 회계등식 오차", safety.accountingIdentityGapPct?.let { String.format("%.2f%%", it) } ?: "-")
                    StageDetailMetric("재무제표 범위", safety.scope.ifBlank { "-" })
                    Spacer(Modifier.height(6.dp))
                    Text("기준: 자본>0 · 부채/자본≤400% · 유동비율≥70% · 회계등식 오차≤5%", fontSize = 10.sp, color = scheme.onSurfaceVariant, lineHeight = 16.sp)
                    if (safety.basis.isNotBlank()) {
                        Spacer(Modifier.height(5.dp))
                        Text(safety.basis, fontSize = 9.sp, color = scheme.onSurfaceVariant, lineHeight = 14.sp)
                    }
                }
            }

            DetailStageCard("STAGE 5 · 상대 PER 밴드") {
                StageDetailMetric("실적 PER", stock.m03Per.rawValue?.let { String.format("%.2f배", it) } ?: "보류")
                StageDetailMetric("시장 상대점수", stock.m03Per.percentileScore?.let { String.format("%.1f / 100", it) } ?: "보류")
                StageDetailMetric("밴드", band?.labelKo ?: if (stock.isLossMaking) "적자 · 밴드 미적용" else "검증 보류")
                Spacer(Modifier.height(6.dp))
                Text("이 밴드는 설명용이며 기존 KR4 종합순위를 다시 계산하거나 바꾸지 않습니다.", fontSize = 10.sp, color = scheme.onSurfaceVariant, lineHeight = 16.sp)
            }

            DetailStageCard("STAGE 6/7 · 최종 조사후보") {
                if (candidate == null) {
                    Text("현재 V2 최종 10종목에는 포함되지 않습니다.", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("최종후보는 4지표 완성 + 양(+)의 실적 PER + 재무안정성 PASS를 모두 만족한 종목 중 기존 KR4 종합순위를 그대로 사용합니다.", fontSize = 11.sp, lineHeight = 18.sp, color = scheme.onSurfaceVariant)
                } else {
                    Text("FINAL ${candidate.candidateRank}위", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
                    Spacer(Modifier.height(5.dp))
                    Text(candidate.selectionReasonKo, fontSize = 12.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(8.dp))
                    StageDetailMetric("기존 KR4 종합순위", "${candidate.sourceRank}위")
                    StageDetailMetric("종합 상대점수", String.format("%.2f", candidate.compositeScore))
                    StageDetailMetric("V2 정책", candidate.policyVersion)
                }
            }

            Button(
                onClick = onOpenFullDetail,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("open_full_stock_detail"),
                shape = RoundedCornerShape(15.dp),
            ) {
                Text("기존 4지표 · 종합해석 · 출처 상세 보기", fontWeight = FontWeight.Bold)
            }
            Text(
                "Stage4~7은 조사 우선순위 보조정보이며 매수·매도 추천이 아닙니다.",
                fontSize = 9.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 18.dp),
            )
        }
    }
}

@Composable
private fun DetailStageCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(15.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(9.dp))
            content()
        }
    }
}

@Composable
private fun StageDetailMetric(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.25f), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

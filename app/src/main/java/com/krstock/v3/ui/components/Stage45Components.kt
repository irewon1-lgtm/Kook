package com.krstock.v3.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.model.FinalCandidateRecord
import com.krstock.v3.data.model.FinancialSafetyRecord
import com.krstock.v3.data.model.FinancialSafetyStatus
import com.krstock.v3.data.model.MetricValue
import com.krstock.v3.data.model.ValuationBand

@Composable
fun FinalCandidateBadge(candidateRank: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.testTag("final_candidate_badge"),
        shape = RoundedCornerShape(999.dp),
        color = scheme.primary,
        contentColor = scheme.onPrimary,
    ) {
        Text(
            "FINAL #$candidateRank",
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun FinancialSafetyBadge(safety: FinancialSafetyRecord?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (label, colors) = when (safety?.status) {
        FinancialSafetyStatus.PASS -> "재무 PASS" to (scheme.secondaryContainer to scheme.onSecondaryContainer)
        FinancialSafetyStatus.FAIL -> "재무 FAIL" to (scheme.errorContainer to scheme.onErrorContainer)
        FinancialSafetyStatus.HOLD -> "재무 HOLD" to (scheme.tertiaryContainer to scheme.onTertiaryContainer)
        FinancialSafetyStatus.NOT_APPLICABLE -> "재무 N/A" to (scheme.surfaceVariant to scheme.onSurfaceVariant)
        null -> "재무 —" to (scheme.surfaceVariant to scheme.onSurfaceVariant)
    }
    Surface(
        modifier = modifier.testTag("financial_safety_badge"),
        shape = RoundedCornerShape(999.dp),
        color = colors.first,
        contentColor = colors.second,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun ValuationBandBadge(band: ValuationBand?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.testTag("valuation_band_badge"),
        shape = RoundedCornerShape(999.dp),
        color = if (band != null) scheme.primaryContainer else scheme.surfaceVariant,
        contentColor = if (band != null) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
    ) {
        Text(
            band?.shortLabelKo ?: "PER 밴드 N/A",
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Compact shared panel for home and the full stock list. */
@Composable
fun Stage45CompactPanel(
    candidate: FinalCandidateRecord?,
    safety: FinancialSafetyRecord?,
    valuation: ValuationBand?,
    modifier: Modifier = Modifier,
    showRatios: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().testTag("stage45_compact_panel"),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceVariant,
        contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (candidate != null) FinalCandidateBadge(candidate.candidateRank)
                FinancialSafetyBadge(safety)
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ValuationBandBadge(valuation)
                Spacer(Modifier.width(8.dp))
                Text(
                    valuation?.let { "PER 상대 ${String.format("%.1f", it.percentile)}" } ?: "양수 PER만 분류",
                    fontSize = 10.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showRatios && safety != null) {
                Spacer(Modifier.height(7.dp))
                Text(
                    buildList {
                        safety.debtToEquityPct?.let { add("부채비율 ${String.format("%.1f", it)}%") }
                        safety.currentRatioPct?.let { add("유동비율 ${String.format("%.1f", it)}%") }
                        safety.accountingIdentityGapPct?.let { add("회계 gap ${String.format("%.2f", it)}%") }
                    }.ifEmpty { listOf(financialSafetyReasonKo(safety.reasonCode)) }.joinToString(" · "),
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                "Stage4=안전성 gate · Stage5=상대 PER 설명 · 기존 조합/종합순위에는 가산하지 않음",
                fontSize = 9.sp,
                lineHeight = 14.sp,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun FinalCandidateDetailCard(candidate: FinalCandidateRecord, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth().testTag("final_candidate_detail_card"),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer),
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.24f)),
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("최종 조사 후보", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Stage6/7 · 기존 KR4 순위 재사용",
                        fontSize = 9.sp,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.72f),
                    )
                }
                FinalCandidateBadge(candidate.candidateRank)
            }
            Spacer(Modifier.height(11.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("최종후보", "${candidate.candidateRank}위", Modifier.weight(1f))
                MiniStat("기존 종합", "${candidate.sourceRank}위", Modifier.weight(1f))
                MiniStat("종합점수", String.format("%.1f", candidate.compositeScore), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(candidate.selectionReasonKo, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
fun FinancialSafetyCard(
    safety: FinancialSafetyRecord?,
    snapshotDate: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth().testTag("financial_safety_card"),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Stage4 재무안정성", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("OpenDART 재무상태표 · 추정값 없음", fontSize = 9.sp, color = scheme.onSurfaceVariant)
                }
                FinancialSafetyBadge(safety)
            }
            Spacer(Modifier.height(10.dp))
            if (safety == null) {
                Text(
                    "활성 정량 스냅샷과 날짜가 일치하는 재무안정성 자료가 없어 fail-closed 처리했습니다.",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = scheme.onSurfaceVariant,
                )
                return@Column
            }

            DetailRow("판정", financialSafetyStatusKo(safety.status))
            safety.debtToEquityPct?.let { DetailRow("부채비율", "${String.format("%.2f", it)}%  (PASS 기준 ≤ 400%)") }
            safety.currentRatioPct?.let { DetailRow("유동비율", "${String.format("%.2f", it)}%  (PASS 기준 ≥ 70%)") }
            safety.accountingIdentityGapPct?.let { DetailRow("회계 identity gap", "${String.format("%.3f", it)}%  (허용 ≤ 5%)") }
            DetailRow("재무 범위", safety.scope.ifBlank { "N/A" })
            DetailRow("기준일", snapshotDate)
            Spacer(Modifier.height(7.dp))
            Text(
                "판정 이유 · ${financialSafetyReasonKo(safety.reasonCode)}",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = when (safety.status) {
                    FinancialSafetyStatus.FAIL -> scheme.error
                    FinancialSafetyStatus.HOLD -> scheme.tertiary
                    else -> scheme.onSurface
                },
            )
            if (safety.basis.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Text("근거", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = scheme.onSurfaceVariant)
                Text(safety.basis, fontSize = 9.sp, lineHeight = 14.sp, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ValuationCard(
    metric: MetricValue,
    band: ValuationBand?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth().testTag("valuation_card"),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Stage5 상대 PER 밴드", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("양수 trailing PER의 시장 상대 분류", fontSize = 9.sp, color = scheme.onSurfaceVariant)
                }
                ValuationBandBadge(band)
            }
            Spacer(Modifier.height(10.dp))
            DetailRow("실적 PER", metric.rawValue?.let { String.format("%.2f배", it) } ?: "N/A")
            DetailRow("PER 상대 percentile", band?.let { String.format("%.1f", it.percentile) } ?: "미적용")
            DetailRow("상대 밴드", band?.labelKo ?: valuationUnavailableReason(metric))
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = scheme.surfaceVariant,
                contentColor = scheme.onSurfaceVariant,
            ) {
                Text(
                    "이 밴드는 설명용입니다. 기존 4지표 종합점수·조합순위·기존 종합순위를 변경하지 않습니다.",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    fontSize = 10.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = scheme.surface.copy(alpha = 0.60f),
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.14f)),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 9.dp)) {
            Text(label, fontSize = 8.sp, color = scheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, fontSize = 10.sp, color = scheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontSize = 10.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

fun financialSafetyStatusKo(status: FinancialSafetyStatus): String = when (status) {
    FinancialSafetyStatus.PASS -> "PASS · 안전성 gate 통과"
    FinancialSafetyStatus.FAIL -> "FAIL · 안전성 기준 탈락"
    FinancialSafetyStatus.HOLD -> "HOLD · 검증 보류"
    FinancialSafetyStatus.NOT_APPLICABLE -> "N/A · 금융업 비교 미적용"
}

fun financialSafetyReasonKo(reason: String): String = when (reason) {
    "PASS" -> "부채비율·유동비율·자본·회계 identity 기준 통과"
    "NONPOSITIVE_EQUITY" -> "자본이 0 이하라 안전성 gate 탈락"
    "DEBT_TO_EQUITY_OVER_400" -> "부채비율이 400%를 초과"
    "CURRENT_RATIO_UNDER_70" -> "유동비율이 70% 미만"
    "ACCOUNTING_IDENTITY_MISMATCH" -> "자산=부채+자본 회계 identity 오차가 허용범위를 초과"
    "NONPOSITIVE_ASSETS" -> "자산총계가 유효한 양수 값이 아님"
    "NONPOSITIVE_CURRENT_LIABILITIES" -> "유동부채가 유효한 양수 값이 아니라 비율 계산 보류"
    "DART_NO_BALANCE_SHEET" -> "OpenDART에서 검증 가능한 재무상태표를 찾지 못함"
    "FINANCIAL_SECTOR_NOT_COMPARABLE" -> "금융업은 일반기업 재무비율 gate와 직접 비교하지 않음"
    "NONFINITE_DERIVED_RATIO" -> "파생 재무비율이 유효한 유한값이 아니라 보류"
    else -> if (reason.startsWith("MISSING_")) "필수 재무항목 누락으로 보류 ($reason)" else reason
}

private fun valuationUnavailableReason(metric: MetricValue): String = when {
    metric.rawValue == null -> "PER 자료 없음"
    metric.rawValue <= 0.0 -> "양수 PER이 아니어서 Stage5 상대밴드 미적용"
    metric.percentileScore == null -> "PER 상대점수가 없어 밴드 미적용"
    else -> "Stage5 밴드 미적용"
}

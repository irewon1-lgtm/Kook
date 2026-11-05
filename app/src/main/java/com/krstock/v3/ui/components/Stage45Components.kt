package com.krstock.v3.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.model.FinalCandidateRecord
import com.krstock.v3.data.model.FinancialSafetyRecord
import com.krstock.v3.data.model.FinancialSafetyStatus
import com.krstock.v3.data.model.MetricValue
import com.krstock.v3.data.model.ValuationBand
import com.krstock.v3.data.repository.StockRepository

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinancialSafetyBadge(
    safety: FinancialSafetyRecord?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    var showDetails by remember { mutableStateOf(false) }
    val (label, colors) = when (safety?.status) {
        FinancialSafetyStatus.PASS -> "자료 확인" to (scheme.surfaceVariant to scheme.onSurface)
        FinancialSafetyStatus.FAIL -> "재무위험" to (Color(0xFFC62828) to Color.White)
        FinancialSafetyStatus.HOLD -> "미확보" to (Color(0xFFEF6C00) to Color.White)
        FinancialSafetyStatus.NOT_APPLICABLE -> "비교제외" to (scheme.background to scheme.onSurfaceVariant)
        null -> "미확보" to (Color(0xFFEF6C00) to Color.White)
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (compact) 0.dp else 48.dp) {
        Surface(
            onClick = { showDetails = true },
            modifier = modifier.testTag("financial_safety_badge"),
            shape = RoundedCornerShape(999.dp),
            color = colors.first,
            contentColor = colors.second,
            border = if (safety?.status == FinancialSafetyStatus.NOT_APPLICABLE) BorderStroke(1.dp, scheme.outline) else null,
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = if (compact) 3.dp else 8.dp, vertical = if (compact) 2.dp else 3.dp),
                fontSize = if (compact) 7.sp else 10.sp,
                lineHeight = if (compact) 9.sp else 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
    if (showDetails) {
        FinancialSafetyEvidenceDialog(safety, StockRepository.quantSnapshotDate()) { showDetails = false }
    }
}

@Composable
private fun FinancialSafetyEvidenceDialog(safety: FinancialSafetyRecord?, snapshotDate: String, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("financial_safety_details"),
        onDismissRequest = onDismiss,
        title = { Text("재무자료와 판정 근거") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                FinancialSafetyEvidenceContent(safety, snapshotDate)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun FinancialSafetyEvidenceContent(safety: FinancialSafetyRecord?, snapshotDate: String) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().testTag("financial_safety_amounts")) {
        financialSafetyDetailRows(safety, snapshotDate).forEach { (label, value) ->
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = if (label == "공식 공시 출처") Modifier.testTag("financial_safety_source") else Modifier,
            )
            Spacer(Modifier.height(9.dp))
        }
        safety?.facts?.sourceUrl?.takeIf {
            it.startsWith("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=") && it.substringAfter("rcpNo=").matches(Regex("[0-9]{14}"))
        }?.let { source ->
            TextButton(onClick = { uriHandler.openUri(source) }) { Text("공식 공시 원문 열기") }
        }
        Text("재무 상태는 4지표 점수·순위를 변경하거나 종목을 숨기지 않습니다", fontSize = 10.sp)
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
                "재무위험 표시는 참고자료입니다. 종목 공개와 기존 4지표 순위에는 영향을 주지 않습니다.",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinancialSafetyCard(
    safety: FinancialSafetyRecord?,
    snapshotDate: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var showDetails by remember { mutableStateOf(false) }
    val facts = safety?.facts
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Card(
            modifier = modifier.fillMaxWidth().testTag("financial_safety_card"),
            shape = RoundedCornerShape(11.dp),
            colors = CardDefaults.cardColors(containerColor = scheme.surface),
            border = BorderStroke(1.dp, scheme.outline),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.width(3.dp).height(18.dp), color = scheme.primary, shape = RoundedCornerShape(3.dp)) {}
                    Spacer(Modifier.width(7.dp))
                    Text("재무현황", fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp)
                    Spacer(Modifier.width(7.dp))
                    Text("Stage4 재무안정성", Modifier.weight(1f), fontSize = 8.sp, lineHeight = 10.sp, color = scheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp), color = scheme.background.copy(alpha = 0.52f),
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("재무 상태", Modifier.width(53.dp), fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold)
                            FinancialSafetyBadge(safety, compact = true)
                            Spacer(Modifier.width(7.dp))
                            Text(
                                when (safety?.status) {
                                    FinancialSafetyStatus.PASS -> "기존 안전성 기준 통과"
                                    FinancialSafetyStatus.FAIL -> "기존 안전성 기준 탈락"
                                    FinancialSafetyStatus.NOT_APPLICABLE -> "금융업 일반비율 비교 제외"
                                    else -> "검증된 자료·요건 확인 보류"
                                },
                                Modifier.weight(1f), fontSize = 8.sp, lineHeight = 10.sp, color = scheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        FinancialStatusRow(
                            "회계 일치", safety?.accountingIdentityGapPct?.let { String.format("오차 %.3f%%", it) } ?: "미확보",
                            "허용 오차 ≤ 5%",
                        )
                        Spacer(Modifier.height(3.dp))
                        FinancialStatusRow(
                            "자료 상태", if (facts?.verified == true) "검증 완료" else if (facts != null) "검증 대기" else "미확보",
                            if (facts?.verified == true) "공식 공시·원문 수치 확인" else "공시·원자료 검증 필요",
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FinancialMetric("유동비율", safety?.currentRatioPct?.let { String.format("%.2f%%", it) } ?: "미확보", Modifier.weight(1f))
                    VerticalDivider(Modifier.height(28.dp), color = scheme.outlineVariant)
                    FinancialMetric("부채비율", safety?.debtToEquityPct?.let { String.format("%.2f%%", it) } ?: "미확보", Modifier.weight(1f))
                    VerticalDivider(Modifier.height(28.dp), color = scheme.outlineVariant)
                    FinancialMetric(
                        "자본총계",
                        facts?.amounts?.get("equity")?.let { amount ->
                            if (facts.currency == "KRW") String.format(java.util.Locale.KOREA, "%,.1f억원", amount / 100_000_000.0)
                            else String.format(java.util.Locale.KOREA, "%,d %s", amount, facts.currency)
                        } ?: "미확보", Modifier.weight(1f),
                    )
                }
                Text(
                    "OpenDART · ${facts?.reportPeriod.orEmpty().ifBlank { "보고기간 미확보" }} · 기준일 $snapshotDate",
                    fontSize = 7.sp, lineHeight = 9.sp, color = scheme.onSurfaceVariant,
                )
            }
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Surface(
                onClick = { showDetails = true },
                modifier = Modifier.fillMaxWidth().testTag("financial_safety_evidence_entry"),
                shape = RoundedCornerShape(9.dp), color = scheme.surface,
                border = BorderStroke(1.dp, scheme.outline),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Canvas(Modifier.size(17.dp)) {
                        val stroke = 1.dp.toPx()
                        drawRect(scheme.onSurfaceVariant, topLeft = Offset(size.width * .15f, size.height * .05f), size = androidx.compose.ui.geometry.Size(size.width * .7f, size.height * .9f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                        for (line in 0..2) {
                            val y = size.height * (.34f + line * .19f)
                            drawLine(scheme.onSurfaceVariant, Offset(size.width * .32f, y), Offset(size.width * .68f, y), stroke)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("근거 및 참고정보", fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("계산식, 실제 금액, 데이터 출처, 기준일 확인", fontSize = 8.sp, lineHeight = 10.sp, color = scheme.onSurfaceVariant)
                    }
                    Text("›", fontSize = 21.sp, lineHeight = 22.sp, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
    if (showDetails) FinancialSafetyEvidenceDialog(safety, snapshotDate) { showDetails = false }
}

@Composable
private fun FinancialStatusRow(label: String, value: String, explanation: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(53.dp), fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold)
        Surface(shape = RoundedCornerShape(999.dp), color = scheme.surfaceVariant) {
            Text(value, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 8.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(7.dp))
        Text(explanation, Modifier.weight(1f), fontSize = 8.sp, lineHeight = 10.sp, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun FinancialMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 8.sp, lineHeight = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2)
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
    "SOURCE_UNAVAILABLE", "COLLECTION_ERROR" -> "공식 공시 서버 응답을 확보하지 못해 검증 보류"
    "DART_IDENTITY_UNCONFIRMED", "ISSUER_NAME_MISMATCH" -> "종목·법인·회사명 매핑을 검증하지 못해 보류"
    "CORRECTION_VALUES_NOT_RECONCILED" -> "정정공시와 원본 금액의 일치 여부를 확인하지 못해 보류"
    "BULK_AMOUNT_MISMATCH" -> "정정공시 API와 재무상태표 원본 금액이 달라 보류"
    "RECEIPT_MISMATCH" -> "정정공시 접수번호와 재무항목 API의 접수번호가 달라 보류"
    "MIXED_PERIOD" -> "서로 다른 보고기간의 원자료가 충돌해 보류"
    "MIXED_CURRENCY", "UNVERIFIED_CURRENCY_UNIT" -> "통화·단위를 검증하지 못해 보류"
    "NONFINITE_DERIVED_RATIO" -> "파생 재무비율이 유효한 유한값이 아니라 보류"
    else -> if (reason.startsWith("MISSING_")) "필수 재무항목 누락으로 보류 ($reason)" else reason
}

private fun valuationUnavailableReason(metric: MetricValue): String = when {
    metric.rawValue == null -> "PER 자료 없음"
    metric.rawValue <= 0.0 -> "양수 PER이 아니어서 Stage5 상대밴드 미적용"
    metric.percentileScore == null -> "PER 상대점수가 없어 밴드 미적용"
    else -> "Stage5 밴드 미적용"
}

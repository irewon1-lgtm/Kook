package com.krstock.v3.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.evidence.ResearchForensicsRepository
import com.krstock.v3.data.model.*
import com.krstock.v3.data.repository.StockRepository

@Composable
internal fun ResearchForensicsPanel(issuerId: String, evidence: EvidenceBundle) {
    val scheme = MaterialTheme.colorScheme
    val summary = remember(issuerId) { StockRepository.getStockDetail(issuerId)?.summary }
    var forensics by remember(issuerId) { mutableStateOf(ResearchForensics(issuerId = issuerId)) }

    LaunchedEffect(issuerId, evidence) {
        if (summary != null && evidence.loaded) {
            forensics = ResearchForensicsRepository.load(summary, evidence)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("forensics_panel"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.24f))
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("PRIMARY SOURCE FORENSICS", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
                    Spacer(Modifier.height(2.dp))
                    Text("DART 원문으로 원인 검증", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                if (!evidence.loaded || !forensics.loaded) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    EvidenceGradeChip(forensics.grade)
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "뉴스 제목이 아니라 공시 원문을 먼저 읽고, 사실·가설·미확인을 분리합니다. 원문 연결이 실패하면 원인을 만들어내지 않습니다.",
                fontSize = 10.sp,
                lineHeight = 16.sp,
                color = scheme.onSurfaceVariant
            )

            if (summary == null) {
                Spacer(Modifier.height(10.dp))
                Text("종목 정보를 확인하지 못해 원문 분석을 보류합니다.", color = scheme.error, fontSize = 11.sp)
                return@Column
            }

            if (!evidence.loaded || !forensics.loaded) {
                Spacer(Modifier.height(12.dp))
                Text("최근 공시와 DART 원문을 교차 확인하는 중입니다.", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                return@Column
            }

            Spacer(Modifier.height(13.dp))
            ForensicsSectionTitle("증거 등급")
            Text(forensics.gradeReason, fontSize = 11.sp, lineHeight = 18.sp)

            Spacer(Modifier.height(13.dp))
            ForensicsSectionTitle("현재 가장 강한 원인 해석")
            Text(forensics.rootCauseRead, fontSize = 12.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)

            if (forensics.currentFilingTitle.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = scheme.surfaceVariant,
                    contentColor = scheme.onSurfaceVariant
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(
                            "현재 원문 · ${forensics.currentFilingTitle}" +
                                forensics.currentFilingDate.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (forensics.previousFilingTitle.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "비교 원문 · ${forensics.previousFilingTitle}" +
                                    forensics.previousFilingDate.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }

            if (forensics.primaryFacts.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = scheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                ForensicsSectionTitle("공시 원문에서 확인된 사실")
                forensics.primaryFacts.take(4).forEach { fact ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = scheme.secondaryContainer.copy(alpha = 0.42f),
                        contentColor = scheme.onSecondaryContainer
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(fact.topic, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = scheme.secondary)
                            Spacer(Modifier.height(3.dp))
                            Text(fact.excerpt, fontSize = 10.sp, lineHeight = 16.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOf(fact.reportTitle, fact.publishedAt).filter { it.isNotBlank() }.joinToString(" · "),
                                fontSize = 8.sp,
                                color = LocalContentColor.current.copy(alpha = 0.70f)
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Text(
                    "현재 읽은 DART 섹션에서 4지표 원인을 직접 설명할 만한 문장을 확보하지 못했습니다. 이것은 ‘원인이 없다’는 뜻이 아니라 ‘현재 근거로 확인하지 못했다’는 뜻입니다.",
                    fontSize = 10.sp,
                    lineHeight = 16.sp,
                    color = scheme.onSurfaceVariant
                )
            }

            val materialChanges = forensics.filingChanges.filter { it.type != FilingChangeType.STABLE }
            if (materialChanges.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = scheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                ForensicsSectionTitle("이전 공시와 달라진 내용")
                Text(
                    "문구 자체의 좋고 나쁨을 자동 판단하지 않고, 새로 등장·삭제·변경된 주제만 표시합니다.",
                    fontSize = 9.sp,
                    lineHeight = 15.sp,
                    color = scheme.onSurfaceVariant
                )
                materialChanges.take(5).forEach { change ->
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(
                            "${changeTypeKo(change.type)} · ${change.topic}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.primary
                        )
                        change.currentExcerpt?.takeIf { it.isNotBlank() }?.let {
                            Text("현재 · $it", fontSize = 10.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        change.previousExcerpt?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                "이전 · $it",
                                fontSize = 9.sp,
                                lineHeight = 15.sp,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }

            if (forensics.industryKpis.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = scheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                ForensicsSectionTitle("이 업종에서 꼭 볼 KPI")
                forensics.industryKpis.forEach { kpi ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (kpi.foundInCurrentFiling) scheme.secondaryContainer else scheme.surfaceVariant
                            ) {
                                Text(
                                    if (kpi.foundInCurrentFiling) "원문 확인" else "읽은 섹션서 미확인",
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (kpi.foundInCurrentFiling) scheme.onSecondaryContainer else scheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(7.dp))
                            Text(kpi.name, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(kpi.whyItMatters, fontSize = 9.sp, lineHeight = 15.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                        kpi.excerpt?.takeIf { it.isNotBlank() }?.let {
                            Text("근거 · $it", fontSize = 9.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }

            if (forensics.alternativeHypotheses.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = scheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                ForensicsSectionTitle("경쟁 가설 — 같은 숫자를 만들 수 있는 다른 이유")
                forensics.alternativeHypotheses.forEach { hypothesis ->
                    Text("• $hypothesis", fontSize = 10.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
            }

            if (forensics.nextChecks.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ForensicsSectionTitle("다음 분기 이 해석을 검증할 것")
                forensics.nextChecks.forEach { check ->
                    Text("• $check", fontSize = 10.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
            }

            if (!forensics.sourceError.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = scheme.errorContainer,
                    contentColor = scheme.onErrorContainer
                ) {
                    Text(
                        "1차 자료 상태 · ${forensics.sourceError}\n원문 확인이 불완전하므로 해당 원인은 확정하지 않습니다.",
                        modifier = Modifier.padding(10.dp),
                        fontSize = 9.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun EvidenceGradeChip(grade: EvidenceGrade) {
    val scheme = MaterialTheme.colorScheme
    val label = when (grade) {
        EvidenceGrade.PRIMARY_CONFIRMED -> "1차자료 확인"
        EvidenceGrade.STRONGLY_SUPPORTED -> "강하게 지지"
        EvidenceGrade.PLAUSIBLE -> "가능성"
        EvidenceGrade.UNVERIFIED -> "미확인"
    }
    val container = when (grade) {
        EvidenceGrade.PRIMARY_CONFIRMED, EvidenceGrade.STRONGLY_SUPPORTED -> scheme.secondaryContainer
        EvidenceGrade.PLAUSIBLE -> scheme.tertiaryContainer
        EvidenceGrade.UNVERIFIED -> scheme.surfaceVariant
    }
    val content = when (grade) {
        EvidenceGrade.PRIMARY_CONFIRMED, EvidenceGrade.STRONGLY_SUPPORTED -> scheme.onSecondaryContainer
        EvidenceGrade.PLAUSIBLE -> scheme.onTertiaryContainer
        EvidenceGrade.UNVERIFIED -> scheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(999.dp), color = container, contentColor = content) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ForensicsSectionTitle(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

private fun changeTypeKo(type: FilingChangeType): String = when (type) {
    FilingChangeType.NEW -> "새로 등장"
    FilingChangeType.REMOVED -> "이전엔 있었으나 이번엔 미확인"
    FilingChangeType.CHANGED -> "표현 변경"
    FilingChangeType.STABLE -> "유사"
}

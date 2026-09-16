package com.krstock.v3.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krstock.v3.data.candidate.FinalCandidateRepository
import com.krstock.v3.data.model.FinalCandidateRecord
import com.krstock.v3.data.model.StockSummary
import com.krstock.v3.data.repository.StockRepository
import com.krstock.v3.data.stage.FinancialSafetyRecord
import com.krstock.v3.data.stage.Stage4567Policy
import com.krstock.v3.data.stage.Stage4567SnapshotRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Stage4567Screen(onStockClick: (String) -> Unit) {
    val context = LocalContext.current
    val stocks = remember { StockRepository.getAllStocks() }
    val candidateByCode = remember {
        FinalCandidateRepository.getFinalCandidates(100).associateBy { it.issuerId }
    }

    var refreshVersion by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadSucceeded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        loading = true
        loadSucceeded = Stage4567SnapshotRepository.bootstrap(context)
        refreshVersion++
        loading = false
    }

    val snapshotDate = StockRepository.quantSnapshotDate()
    val safetyByCode = remember(refreshVersion, snapshotDate) {
        Stage4567SnapshotRepository.current(snapshotDate)
    }
    val coverage = remember(refreshVersion, snapshotDate) {
        Stage4567SnapshotRepository.coverage(snapshotDate)
    }

    var search by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") }
    var finalOnly by remember { mutableStateOf(false) }

    val filtered = remember(stocks, safetyByCode, candidateByCode, search, statusFilter, finalOnly) {
        val q = search.trim()
        stocks.asSequence()
            .filter { stock ->
                q.isBlank() || stock.name.contains(q, true) || stock.issuerId.contains(q, true) || stock.sector.contains(q, true)
            }
            .filter { stock ->
                statusFilter == "ALL" || safetyByCode[stock.issuerId]?.status == statusFilter
            }
            .filter { stock -> !finalOnly || candidateByCode.containsKey(stock.issuerId) }
            .sortedWith(
                compareBy<StockSummary> { candidateByCode[it.issuerId]?.candidateRank == null }
                    .thenBy { candidateByCode[it.issuerId]?.candidateRank ?: Int.MAX_VALUE }
                    .thenBy { it.rankOrder == null }
                    .thenBy { it.rankOrder ?: Int.MAX_VALUE }
                    .thenBy { it.issuerId }
            )
            .toList()
    }

    val scheme = MaterialTheme.colorScheme
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
                title = {
                    Column {
                        Text("안정 · 가치 · 최종후보", fontWeight = FontWeight.Bold, fontSize = 21.sp)
                        Text(
                            "Stage4 재무안정성 · Stage5 상대 PER · Stage6/7 최종후보 V2",
                            fontSize = 11.sp,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                .testTag("stage4567_list"),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Stage4567SummaryCard(
                    loading = loading,
                    loadSucceeded = loadSucceeded,
                    snapshotDate = snapshotDate,
                    coverage = coverage,
                    finalCount = candidateByCode.size,
                )
            }

            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().testTag("stage4567_search"),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("회사명 · 종목코드 · 업종 검색") },
                    label = { Text("2,649종목 검색") },
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                ) {
                    Column(Modifier.padding(13.dp)) {
                        Text("재무안정성 필터", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(7.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("ALL" to "전체", "PASS" to "통과", "FAIL" to "탈락").forEach { (code, label) ->
                                FilterChip(
                                    selected = statusFilter == code,
                                    onClick = { statusFilter = code },
                                    modifier = Modifier.weight(1f),
                                    label = { Text(label, fontSize = 11.sp) },
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("HOLD" to "보류", "NOT_APPLICABLE" to "비교제외").forEach { (code, label) ->
                                FilterChip(
                                    selected = statusFilter == code,
                                    onClick = { statusFilter = code },
                                    modifier = Modifier.weight(1f),
                                    label = { Text(label, fontSize = 11.sp) },
                                )
                            }
                            FilterChip(
                                selected = finalOnly,
                                onClick = { finalOnly = !finalOnly },
                                modifier = Modifier.weight(1f),
                                label = { Text(if (finalOnly) "✓ 최종후보" else "최종후보만", fontSize = 11.sp) },
                            )
                        }
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    color = scheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("검색 결과 ${filtered.size}개", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(
                            "밴드는 순위를 바꾸지 않음",
                            fontSize = 10.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!loading && safetyByCode.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = scheme.errorContainer),
                    ) {
                        Text(
                            "Stage4 스냅샷을 검증하지 못해 재무안정성 표시는 fail-closed 상태입니다. 기존 4지표 순위는 그대로 유지됩니다.",
                            modifier = Modifier.padding(14.dp),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = scheme.onErrorContainer,
                        )
                    }
                }
            }

            items(filtered, key = { "stage:${it.market}:${it.issuerId}" }) { stock ->
                Stage4567StockCard(
                    stock = stock,
                    safety = safetyByCode[stock.issuerId],
                    candidate = candidateByCode[stock.issuerId],
                    onClick = { onStockClick(stock.issuerId) },
                )
            }
        }
    }
}

@Composable
private fun Stage4567SummaryCard(
    loading: Boolean,
    loadSucceeded: Boolean,
    snapshotDate: String,
    coverage: com.krstock.v3.data.stage.FinancialSafetyCoverage?,
    finalCount: Int,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().testTag("stage4567_summary"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer),
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.2f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("STAGE 4→7 LIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
                    Text("재무안정성부터 최종후보까지 앱 반영", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("정량 스냅샷 $snapshotDate와 날짜가 다르면 표시하지 않습니다.", fontSize = 10.sp, color = scheme.onSurfaceVariant)
                }
                if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(12.dp))
            if (coverage != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CountPill("PASS", coverage.pass, Modifier.weight(1f))
                    CountPill("FAIL", coverage.fail, Modifier.weight(1f))
                    CountPill("HOLD", coverage.hold, Modifier.weight(1f))
                    CountPill("N/A", coverage.notApplicable, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "전체 ${coverage.total}종목 · 최종 조사후보 ${finalCount}종목",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            } else if (!loading) {
                Text(
                    if (loadSucceeded) "Stage4 데이터 대기 중" else "Stage4 데이터 검증 실패 · 기존 순위는 유지",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CountPill(label: String, value: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = scheme.surface.copy(alpha = 0.62f)) {
        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 9.sp, color = scheme.onSurfaceVariant)
            Text(value.toString(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Stage4567StockCard(
    stock: StockSummary,
    safety: FinancialSafetyRecord?,
    candidate: FinalCandidateRecord?,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val band = Stage4567Policy.valuationBand(stock.m03Per.rawValue, stock.m03Per.percentileScore)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, if (candidate != null) scheme.primary.copy(alpha = 0.45f) else scheme.outlineVariant),
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(stock.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${stock.issuerId} · ${stock.market} · 기본순위 ${stock.rankOrder?.let { "${it}위" } ?: "보류"}",
                        fontSize = 10.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (candidate != null) {
                    Pill("FINAL ${candidate.candidateRank}위", scheme.primaryContainer, scheme.onPrimaryContainer)
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                val status = safety?.status ?: "UNAVAILABLE"
                Pill(
                    "안정 ${Stage4567Policy.safetyLabelKo(status)}",
                    safetyContainer(status),
                    safetyContent(status),
                    Modifier.weight(1f),
                )
                Pill(
                    band?.labelKo ?: if (stock.isLossMaking) "적자 · PER 밴드 미적용" else "PER 밴드 보류",
                    scheme.secondaryContainer,
                    scheme.onSecondaryContainer,
                    Modifier.weight(2f),
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StageStat("부채/자본", pct(safety?.debtToEquityPct), Modifier.weight(1f))
                StageStat("유동비율", pct(safety?.currentRatioPct), Modifier.weight(1f))
                StageStat("회계오차", pct(safety?.accountingIdentityGapPct), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StageStat("실적 PER", stock.m03Per.rawValue?.let { String.format("%.2f배", it) } ?: "보류", Modifier.weight(1f))
                StageStat("PER 상대점수", stock.m03Per.percentileScore?.let { String.format("%.1f", it) } ?: "보류", Modifier.weight(1f))
            }

            safety?.let {
                Spacer(Modifier.height(9.dp))
                Text(
                    "Stage4: ${Stage4567Policy.safetyReasonKo(it.reason)}",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = scheme.onSurfaceVariant,
                )
                if (it.basis.isNotBlank()) {
                    Text(
                        it.basis,
                        fontSize = 9.sp,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            candidate?.let {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp),
                    color = scheme.primaryContainer.copy(alpha = 0.65f),
                ) {
                    Text(
                        it.selectionReasonKo,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun StageStat(label: String, value: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = scheme.surfaceVariant) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
            Text(label, fontSize = 9.sp, color = scheme.onSurfaceVariant)
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Pill(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(999.dp), color = container, contentColor = content) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun safetyContainer(status: String): androidx.compose.ui.graphics.Color {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        "PASS" -> scheme.primaryContainer
        "FAIL" -> scheme.errorContainer
        "HOLD" -> scheme.tertiaryContainer
        else -> scheme.surfaceVariant
    }
}

@Composable
private fun safetyContent(status: String): androidx.compose.ui.graphics.Color {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        "PASS" -> scheme.onPrimaryContainer
        "FAIL" -> scheme.onErrorContainer
        "HOLD" -> scheme.onTertiaryContainer
        else -> scheme.onSurfaceVariant
    }
}

private fun pct(value: Double?): String = value?.let { String.format("%.1f%%", it) } ?: "-"

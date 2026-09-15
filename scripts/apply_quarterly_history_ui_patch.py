#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/krstock/v3/ui/screens/StockDetailScreen.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    text = text.replace(old, new, 1)


replace_once(
    "import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.platform.testTag\n",
    "import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.platform.testTag\n",
    "LocalContext import",
)
replace_once(
    "import com.krstock.v3.data.analysis.IntegratedInterpretationEngine\nimport com.krstock.v3.data.evidence.ContextEvidenceRepository\n",
    "import com.krstock.v3.data.analysis.IntegratedInterpretationEngine\nimport com.krstock.v3.data.analysis.QuarterlyTrendAnalyzer\nimport com.krstock.v3.data.evidence.ContextEvidenceRepository\nimport com.krstock.v3.data.history.QuarterlyHistoryRepository\n",
    "quarterly imports",
)
replace_once(
    """    val summary = stockDetail.summary
    val report = stockDetail.report
    var evidence by remember(issuerId) { mutableStateOf(EvidenceBundle(issuerId = issuerId)) }
    LaunchedEffect(issuerId) {
        evidence = ContextEvidenceRepository.load(issuerId)
    }
    val integrated = remember(summary, evidence) {
        IntegratedInterpretationEngine.analyze(summary, evidence)
    }
""",
    """    val summary = stockDetail.summary
    val report = stockDetail.report
    val context = LocalContext.current
    var evidence by remember(issuerId) { mutableStateOf(EvidenceBundle(issuerId = issuerId)) }
    var quarterlyHistory by remember(issuerId) { mutableStateOf(QuarterlyHistory.empty(issuerId)) }
    LaunchedEffect(issuerId) {
        evidence = ContextEvidenceRepository.load(issuerId)
    }
    LaunchedEffect(issuerId) {
        quarterlyHistory = QuarterlyHistoryRepository.load(context, issuerId)
    }
    val integrated = remember(summary, evidence, quarterlyHistory) {
        QuarterlyTrendAnalyzer.enrich(
            IntegratedInterpretationEngine.analyze(summary, evidence),
            quarterlyHistory
        )
    }
""",
    "quarterly state",
)
replace_once(
    "                    2 -> DetailAnalysisPage(analysis = integrated, evidence = evidence)\n",
    "                    2 -> DetailAnalysisPage(analysis = integrated, evidence = evidence, history = quarterlyHistory)\n",
    "analysis page call",
)
replace_once(
    "private fun DetailAnalysisPage(analysis: IntegratedAnalysis, evidence: EvidenceBundle) {\n",
    "private fun DetailAnalysisPage(analysis: IntegratedAnalysis, evidence: EvidenceBundle, history: QuarterlyHistory) {\n",
    "analysis page signature",
)
replace_once(
    """        AnalysisCard("CORE READ", analysis.regimeTitle) {
            Text(analysis.thesis, fontSize = 12.sp, lineHeight = 20.sp)
        }

        AnalysisCard("INDUSTRY MAP", "이 산업에서 숫자를 읽는 법") {
""",
    """        AnalysisCard("CORE READ", analysis.regimeTitle) {
            Text(analysis.thesis, fontSize = 12.sp, lineHeight = 20.sp)
        }

        QuarterlyHistoryCard(analysis = analysis, history = history)

        AnalysisCard("INDUSTRY MAP", "이 산업에서 숫자를 읽는 법") {
""",
    "quarterly card insertion",
)

marker = """@Composable
private fun EvidenceRow(item: ContextEvidence) {
"""
card = """@Composable
private fun QuarterlyHistoryCard(analysis: IntegratedAnalysis, history: QuarterlyHistory) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().testTag("quarterly_history_panel"),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("4–8 QUARTER TREND", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
                    Text("실제 분기 숫자가 어떻게 변했는지", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = scheme.secondaryContainer,
                    contentColor = scheme.onSecondaryContainer
                ) {
                    Text(
                        analysis.quarterlyCoverage.ifBlank { "확인 중" },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            Text(analysis.quarterlyTrend, fontSize = 12.sp, lineHeight = 20.sp)

            if (history.points.isNotEmpty()) {
                Spacer(Modifier.height(11.dp))
                HorizontalDivider(color = scheme.outlineVariant)
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("분기", modifier = Modifier.weight(0.72f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("매출", modifier = Modifier.weight(1.25f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("YoY", modifier = Modifier.weight(0.85f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("영업률", modifier = Modifier.weight(0.85f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                history.points.takeLast(8).asReversed().forEach { point ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(point.period, modifier = Modifier.weight(0.72f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            QuarterlyTrendAnalyzer.formatAmount(point.revenue),
                            modifier = Modifier.weight(1.25f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            QuarterlyTrendAnalyzer.formatPct(point.revenueYoY),
                            modifier = Modifier.weight(0.85f),
                            fontSize = 10.sp
                        )
                        Text(
                            QuarterlyTrendAnalyzer.formatPct(point.operatingMargin),
                            modifier = Modifier.weight(0.85f),
                            fontSize = 10.sp
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    listOf(history.source, history.generatedAt).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 8.sp,
                    color = scheme.onSurfaceVariant
                )
            } else if (history.loaded) {
                Spacer(Modifier.height(8.dp))
                Text("해당 종목은 아직 검증 가능한 4분기 이상 시계열이 없습니다.", fontSize = 10.sp, color = scheme.onSurfaceVariant)
            } else {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

"""
replace_once(marker, card + marker, "quarterly card function")

path.write_text(text, encoding="utf-8")
print("QUARTERLY_UI_PATCH_PASS", path)

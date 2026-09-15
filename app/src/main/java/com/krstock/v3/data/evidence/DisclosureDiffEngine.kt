package com.krstock.v3.data.evidence

import com.krstock.v3.data.model.ContextEvidence
import com.krstock.v3.data.model.DisclosureDiff
import com.krstock.v3.data.model.EvidenceKind

/**
 * DART change detector.
 *
 * Full-text differences are emitted only when two primary disclosure bodies are
 * actually available. If bodies are unavailable, the engine may expose a
 * lower-confidence disclosure-timeline change, but it explicitly marks that
 * as a fallback rather than calling it a document diff.
 */
object DisclosureDiffEngine {
    private data class Signal(val label: String, val words: List<String>)

    private val signals = listOf(
        Signal("신규 위험·불확실성", listOf("위험", "불확실", "소송", "규제", "제재", "중단", "차질", "부진")),
        Signal("CAPEX·생산능력", listOf("설비투자", "capex", "증설", "생산능력", "공장", "신규라인", "가동")),
        Signal("수주·고객·수요", listOf("수주", "수주잔고", "공급계약", "고객사", "수요", "납품")),
        Signal("가격·제품믹스", listOf("판가", "가격인상", "가격인하", "고부가", "제품믹스", "제품구성")),
        Signal("원가·수율·비용", listOf("원가", "비용", "인건비", "감가상각", "수율", "손실")),
        Signal("신사업·신제품", listOf("신사업", "신규사업", "신제품", "출시", "진출", "신규서비스")),
        Signal("R&D·임상·허가", listOf("연구개발", "r&d", "임상", "허가", "승인", "파이프라인")),
        Signal("자금조달·희석", listOf("유상증자", "전환사채", "신주인수권", "자금조달", "차입", "증자")),
        Signal("경영진 전망·전략", listOf("전망", "목표", "계획", "전략", "집중", "확대", "축소", "개선"))
    )

    fun compare(disclosures: List<ContextEvidence>): DisclosureDiff? {
        val primary = disclosures
            .filter { it.kind == EvidenceKind.DISCLOSURE }
            .sortedByDescending { it.publishedAt }
        if (primary.isEmpty()) return null

        val periodic = primary.filter(::isPeriodicReport)
        val current = periodic.getOrNull(0)
        val previous = periodic.getOrNull(1)

        if (current != null && previous != null &&
            current.bodyText.normalizedBody().length >= 400 &&
            previous.bodyText.normalizedBody().length >= 400
        ) {
            return fullTextDiff(current, previous)
        }

        return timelineFallback(primary, current, previous)
    }

    private fun fullTextDiff(current: ContextEvidence, previous: ContextEvidence): DisclosureDiff {
        val now = current.bodyText.normalizedBody()
        val before = previous.bodyText.normalizedBody()
        val nowCounts = signalCounts(now)
        val beforeCounts = signalCounts(before)

        val appeared = mutableListOf<String>()
        val disappeared = mutableListOf<String>()
        val strengthened = mutableListOf<String>()
        val weakened = mutableListOf<String>()

        signals.forEach { signal ->
            val n = nowCounts[signal.label] ?: 0
            val b = beforeCounts[signal.label] ?: 0
            when {
                b == 0 && n > 0 -> appeared += signal.label
                b > 0 && n == 0 -> disappeared += signal.label
                b > 0 && n >= b + maxOf(2, b / 2) -> strengthened += signal.label
                n > 0 && b >= n + maxOf(2, n / 2) -> weakened += signal.label
            }
        }

        return DisclosureDiff(
            available = true,
            scope = "DART 원문 본문 Diff",
            currentTitle = current.title,
            previousTitle = previous.title,
            newlyAppeared = appeared,
            disappeared = disappeared,
            strengthened = strengthened,
            weakened = weakened,
            note = if (appeared.isEmpty() && disappeared.isEmpty() && strengthened.isEmpty() && weakened.isEmpty()) {
                "두 원문에서 핵심 사업동인·위험 키워드의 의미 있는 방향 변화가 잡히지 않았습니다. 문장 단위로 달라져도 핵심 신호가 같으면 변화로 과장하지 않습니다."
            } else {
                "현재 보고서와 직전 정기보고서의 실제 본문을 비교했습니다. 단어 개수 자체가 투자 결론은 아니며, 변화가 4지표·업종 KPI와 같은 방향인지 추가 교차확인합니다."
            }
        )
    }

    private fun timelineFallback(
        primary: List<ContextEvidence>,
        current: ContextEvidence?,
        previous: ContextEvidence?
    ): DisclosureDiff {
        val recent = primary.take(6).joinToString(" ") { it.title }
        val older = primary.drop(6).take(6).joinToString(" ") { it.title }
        val recentSignals = activeSignals(recent)
        val olderSignals = activeSignals(older)
        val appeared = (recentSignals - olderSignals).toList()
        val disappeared = (olderSignals - recentSignals).toList()

        return DisclosureDiff(
            available = false,
            scope = "공시 목록 변화(본문 Diff 대체 불가)",
            currentTitle = current?.title.orEmpty(),
            previousTitle = previous?.title.orEmpty(),
            newlyAppeared = appeared,
            disappeared = disappeared,
            note = "정기보고서 원문 두 개가 확보되지 않아 본문 redline은 확정하지 않았습니다. 최근 DART 공시 목록에서 새로 등장하거나 사라진 주제만 보조 신호로 사용합니다."
        )
    }

    private fun signalCounts(text: String): Map<String, Int> = signals.associate { signal ->
        signal.label to signal.words.sumOf { word -> Regex(Regex.escape(word), RegexOption.IGNORE_CASE).findAll(text).count() }
    }

    private fun activeSignals(text: String): Set<String> = signals
        .filter { signal -> signal.words.any { text.contains(it, ignoreCase = true) } }
        .mapTo(linkedSetOf()) { it.label }

    private fun isPeriodicReport(item: ContextEvidence): Boolean {
        val title = item.title.replace(" ", "")
        return title.contains("사업보고서") || title.contains("반기보고서") || title.contains("분기보고서")
    }

    private fun String.normalizedBody(): String = this
        .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()
}

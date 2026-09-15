package com.krstock.v3.data.evidence

import com.krstock.v3.data.analysis.IndustryKpiDefinition
import com.krstock.v3.data.model.ContextEvidence
import com.krstock.v3.data.model.FilingChangeType
import com.krstock.v3.data.model.FilingTopicChange
import com.krstock.v3.data.model.IndustryKpiCheck
import com.krstock.v3.data.model.PrimarySourceExcerpt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

data class DartPrimaryBundle(
    val loaded: Boolean = false,
    val currentTitle: String = "",
    val currentDate: String = "",
    val previousTitle: String = "",
    val previousDate: String = "",
    val excerpts: List<PrimarySourceExcerpt> = emptyList(),
    val changes: List<FilingTopicChange> = emptyList(),
    val kpiChecks: List<IndustryKpiCheck> = emptyList(),
    val error: String? = null
)

/**
 * Reads public DART filing pages without embedding an API key in the APK.
 *
 * The repository is intentionally fail-closed. If DART changes its public
 * viewer structure, the result becomes UNVERIFIED upstream rather than falling
 * back to an invented explanation. Only short, relevant excerpts are retained
 * in memory; full third-party filing text is never persisted by the app.
 */
object DartPrimarySourceRepository {
    private const val DART_MAIN = "https://dart.fss.or.kr/dsaf001/main.do"
    private const val DART_VIEWER = "https://dart.fss.or.kr/report/viewer.do"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 6_000
    private const val MAX_CURRENT_SECTIONS = 5
    private const val MAX_PREVIOUS_SECTIONS = 4
    private const val MAX_EXCERPTS = 8
    private const val MAX_SECTION_TEXT = 18_000
    private val cache = ConcurrentHashMap<String, DartPrimaryBundle>()

    suspend fun load(
        issuerId: String,
        disclosures: List<ContextEvidence>,
        kpis: List<IndustryKpiDefinition>
    ): DartPrimaryBundle {
        val cacheKey = buildString {
            append(issuerId)
            append('|')
            disclosures.take(6).forEach { append(it.receiptNo.ifBlank { it.id }).append(':').append(it.publishedAt).append('|') }
        }
        cache[cacheKey]?.let { return it }
        val loaded = withContext(Dispatchers.IO) { fetch(disclosures, kpis) }
        cache[cacheKey] = loaded
        return loaded
    }

    private fun fetch(
        disclosures: List<ContextEvidence>,
        kpis: List<IndustryKpiDefinition>
    ): DartPrimaryBundle {
        val candidates = disclosures
            .mapNotNull { evidence -> receiptOf(evidence)?.let { receipt -> FilingCandidate(evidence, receipt) } }
            .distinctBy { it.receiptNo }
            .sortedByDescending { it.evidence.publishedAt }

        if (candidates.isEmpty()) {
            return DartPrimaryBundle(loaded = true, error = "최근 공시에서 DART 접수번호를 확인하지 못함")
        }

        val periodic = candidates.filter { isPeriodic(it.evidence.title) }
        val selected = when {
            periodic.size >= 2 -> periodic.take(2)
            periodic.size == 1 -> listOf(periodic.first()) + candidates.filterNot { it.receiptNo == periodic.first().receiptNo }.take(1)
            else -> candidates.take(2)
        }
        val currentCandidate = selected.firstOrNull()
            ?: return DartPrimaryBundle(loaded = true, error = "DART 원문 후보 없음")

        return try {
            val searchTerms = buildSearchTerms(kpis)
            val current = fetchFiling(currentCandidate, searchTerms, MAX_CURRENT_SECTIONS)
                ?: return DartPrimaryBundle(loaded = true, error = "최신 DART 원문 구조를 해석하지 못함")
            val previous = selected.getOrNull(1)?.let { fetchFiling(it, searchTerms, MAX_PREVIOUS_SECTIONS) }

            val currentExcerpts = extractExcerpts(current, searchTerms)
            val changes = previous?.let { buildChanges(current, it, kpis) }.orEmpty()
            val kpiChecks = buildKpiChecks(current, kpis)

            DartPrimaryBundle(
                loaded = true,
                currentTitle = current.title,
                currentDate = current.date,
                previousTitle = previous?.title.orEmpty(),
                previousDate = previous?.date.orEmpty(),
                excerpts = currentExcerpts,
                changes = changes,
                kpiChecks = kpiChecks
            )
        } catch (t: Throwable) {
            DartPrimaryBundle(
                loaded = true,
                error = "DART 원문 확인 실패: ${t.javaClass.simpleName}"
            )
        }
    }

    private data class FilingCandidate(val evidence: ContextEvidence, val receiptNo: String)

    private data class ViewerSection(
        val rcpNo: String,
        val dcmNo: String,
        val eleId: String,
        val offset: String,
        val length: String,
        val dtd: String,
        val context: String
    ) {
        val stableKey: String get() = "$dcmNo:$eleId:$offset:$length"
    }

    private data class FilingText(
        val receiptNo: String,
        val title: String,
        val date: String,
        val sourceUrl: String,
        val paragraphs: List<String>
    )

    private fun fetchFiling(
        candidate: FilingCandidate,
        searchTerms: Set<String>,
        maxSections: Int
    ): FilingText? {
        val mainUrl = "$DART_MAIN?rcpNo=${candidate.receiptNo}"
        val main = get(mainUrl)
        val sections = parseViewerSections(main)
        if (sections.isEmpty()) return null

        val ranked = sections
            .distinctBy { it.stableKey }
            .sortedWith(
                compareByDescending<ViewerSection> { sectionScore(it.context, searchTerms) }
                    .thenBy { it.eleId.toIntOrNull() ?: Int.MAX_VALUE }
            )
            .take(maxSections)

        val paragraphs = mutableListOf<String>()
        for (section in ranked) {
            val url = viewerUrl(section)
            val body = runCatching { get(url) }.getOrNull() ?: continue
            paragraphs += htmlToParagraphs(body).take(120)
            if (paragraphs.sumOf { it.length } >= MAX_SECTION_TEXT * maxSections) break
        }
        val deduped = paragraphs
            .map { normalizeWhitespace(it) }
            .filter { it.length in 18..900 }
            .distinctBy { normalizedKey(it) }
        if (deduped.isEmpty()) return null

        return FilingText(
            receiptNo = candidate.receiptNo,
            title = candidate.evidence.title,
            date = candidate.evidence.publishedAt,
            sourceUrl = mainUrl,
            paragraphs = deduped
        )
    }

    internal fun parseViewerSections(mainHtml: String): List<ViewerSection> {
        val decoded = decodeEntities(mainHtml)
        val pattern = Regex(
            """viewDoc\(\s*['\"](\d{14})['\"]\s*,\s*['\"](\d+)['\"]\s*,\s*['\"](\d+)['\"]\s*,\s*['\"](\d+)['\"]\s*,\s*['\"](\d+)['\"]\s*,\s*['\"]([^'\"]+)['\"]""",
            setOf(RegexOption.IGNORE_CASE)
        )
        return pattern.findAll(decoded).map { match ->
            val start = max(0, match.range.first - 600)
            val end = min(decoded.length, match.range.last + 260)
            ViewerSection(
                rcpNo = match.groupValues[1],
                dcmNo = match.groupValues[2],
                eleId = match.groupValues[3],
                offset = match.groupValues[4],
                length = match.groupValues[5],
                dtd = match.groupValues[6],
                context = stripMarkup(decoded.substring(start, end)).takeLast(900)
            )
        }.toList()
    }

    internal fun htmlToParagraphs(html: String): List<String> {
        var text = html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?i)<br\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|tr|li|h[1-6]|table|section)>"), "\n")
            .replace(Regex("(?is)<[^>]+>"), " ")
        text = decodeEntities(text)
        return text.split(Regex("[\r\n]+"))
            .map(::normalizeWhitespace)
            .filter { it.isNotBlank() }
            .flatMap { paragraph ->
                if (paragraph.length <= 900) listOf(paragraph)
                else paragraph.chunked(700)
            }
    }

    private fun extractExcerpts(filing: FilingText, searchTerms: Set<String>): List<PrimarySourceExcerpt> {
        val causal = CAUSAL_MARKERS
        return filing.paragraphs
            .map { paragraph ->
                val lower = paragraph.lowercase()
                val termScore = searchTerms.count { lower.contains(it) }
                val causalScore = causal.count { lower.contains(it) }
                val score = termScore * 4 + causalScore * 3 + if (paragraph.length in 50..420) 2 else 0
                paragraph to score
            }
            .filter { it.second >= 5 }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy(::normalizedKey)
            .take(MAX_EXCERPTS)
            .map { paragraph ->
                PrimarySourceExcerpt(
                    receiptNo = filing.receiptNo,
                    reportTitle = filing.title,
                    publishedAt = filing.date,
                    topic = inferTopic(paragraph),
                    excerpt = paragraph.take(420),
                    sourceUrl = filing.sourceUrl
                )
            }
    }

    private fun buildChanges(
        current: FilingText,
        previous: FilingText,
        kpis: List<IndustryKpiDefinition>
    ): List<FilingTopicChange> {
        val topics = mutableListOf(
            Topic("수요·수주", listOf("수주", "주문", "계약", "고객", "판매량", "물동량")),
            Topic("투자·R&D", listOf("연구개발", "r&d", "설비투자", "시설투자", "증설", "capex")),
            Topic("원가·수익성", listOf("원가", "원재료", "비용", "수익성", "영업이익", "마진")),
            Topic("가격·제품믹스", listOf("판매가격", "판가", "가격", "asp", "제품믹스", "고부가")),
            Topic("생산·가동", listOf("가동률", "수율", "생산능력", "생산량", "capa")),
            Topic("재고·운전자본", listOf("재고", "매출채권", "운전자본", "재고자산"))
        )
        kpis.take(4).forEach { kpi -> topics += Topic("산업 KPI · ${kpi.name}", kpi.terms) }

        return topics.mapNotNull { topic ->
            val currentText = bestParagraph(current.paragraphs, topic.terms)
            val previousText = bestParagraph(previous.paragraphs, topic.terms)
            val type = when {
                currentText == null && previousText == null -> return@mapNotNull null
                currentText != null && previousText == null -> FilingChangeType.NEW
                currentText == null && previousText != null -> FilingChangeType.REMOVED
                similarity(currentText!!, previousText!!) < 0.62 -> FilingChangeType.CHANGED
                else -> FilingChangeType.STABLE
            }
            FilingTopicChange(
                topic = topic.name,
                type = type,
                currentExcerpt = currentText?.take(360),
                previousExcerpt = previousText?.take(360)
            )
        }.sortedBy { if (it.type == FilingChangeType.STABLE) 1 else 0 }
            .take(10)
    }

    private fun buildKpiChecks(filing: FilingText, kpis: List<IndustryKpiDefinition>): List<IndustryKpiCheck> =
        kpis.take(6).map { kpi ->
            val excerpt = bestParagraph(filing.paragraphs, kpi.terms)
            IndustryKpiCheck(
                name = kpi.name,
                whyItMatters = kpi.whyItMatters,
                foundInCurrentFiling = excerpt != null,
                excerpt = excerpt?.take(320)
            )
        }

    private data class Topic(val name: String, val terms: List<String>)

    private fun bestParagraph(paragraphs: List<String>, terms: List<String>): String? = paragraphs
        .map { p -> p to terms.sumOf { term -> if (p.contains(term, ignoreCase = true)) 1 else 0 } }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length })
        .firstOrNull()?.first

    private fun similarity(a: String, b: String): Double {
        val at = tokens(a)
        val bt = tokens(b)
        if (at.isEmpty() || bt.isEmpty()) return 0.0
        val intersection = at.intersect(bt).size.toDouble()
        val union = at.union(bt).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    private fun tokens(text: String): Set<String> = text.lowercase()
        .split(Regex("[^0-9a-z가-힣]+"))
        .filter { it.length >= 2 }
        .toSet()

    private fun sectionScore(context: String, searchTerms: Set<String>): Int {
        val lower = context.lowercase()
        var score = searchTerms.sumOf { if (lower.contains(it)) 4 else 0 }
        score += SECTION_PRIORITIES.sumOf { if (lower.contains(it)) 7 else 0 }
        if (lower.contains("재무제표")) score -= 2 // structured figures are already handled by the quant collector
        return score
    }

    private fun buildSearchTerms(kpis: List<IndustryKpiDefinition>): Set<String> = buildSet {
        addAll(SECTION_PRIORITIES)
        addAll(CAUSAL_MARKERS)
        addAll(listOf("매출", "영업이익", "수익성", "원가", "가격", "판매량", "수주", "투자", "연구개발", "재고"))
        kpis.forEach { kpi -> kpi.terms.forEach { add(it.lowercase()) } }
    }

    private fun inferTopic(text: String): String {
        val lower = text.lowercase()
        return when {
            listOf("연구개발", "r&d", "설비투자", "증설", "capex").any(lower::contains) -> "투자·R&D"
            listOf("원가", "원재료", "비용", "마진", "수익성").any(lower::contains) -> "원가·수익성"
            listOf("가격", "판가", "asp", "제품믹스", "고부가").any(lower::contains) -> "가격·제품믹스"
            listOf("수주", "계약", "주문", "고객", "판매량").any(lower::contains) -> "수요·수주"
            listOf("가동률", "수율", "생산능력", "생산량").any(lower::contains) -> "생산·가동"
            else -> "사업·실적"
        }
    }

    private fun isPeriodic(title: String): Boolean =
        listOf("사업보고서", "반기보고서", "분기보고서").any { title.contains(it) }

    private fun receiptOf(evidence: ContextEvidence): String? {
        val direct = evidence.receiptNo.ifBlank { evidence.id }
        Regex("\\d{14}").find(direct)?.value?.let { return it }
        Regex("(?:rcpNo|rceptNo|receiptNo)=?(\\d{14})", RegexOption.IGNORE_CASE)
            .find(evidence.url)?.groupValues?.getOrNull(1)?.let { return it }
        return Regex("\\b20\\d{12}\\b").find(evidence.url)?.value
    }

    private fun viewerUrl(section: ViewerSection): String {
        fun e(v: String) = URLEncoder.encode(v, StandardCharsets.UTF_8.name())
        return "$DART_VIEWER?rcpNo=${e(section.rcpNo)}&dcmNo=${e(section.dcmNo)}&eleId=${e(section.eleId)}" +
            "&offset=${e(section.offset)}&length=${e(section.length)}&dtd=${e(section.dtd)}"
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Safari/537.36 KR4/2.0")
            setRequestProperty("Referer", "https://dart.fss.or.kr/")
        }
        try {
            val status = conn.responseCode
            if (status !in 200..299) error("DART_HTTP_$status")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun stripMarkup(text: String): String = normalizeWhitespace(
        decodeEntities(text)
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<[^>]+>"), " ")
    )

    private fun decodeEntities(text: String): String {
        var result = text
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
        result = Regex("&#(\\d+);").replace(result) { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it in 32..0x10FFFF }?.let { code ->
                runCatching { String(Character.toChars(code)) }.getOrNull()
            } ?: m.value
        }
        return result
    }

    private fun normalizeWhitespace(text: String): String = text.replace(Regex("\\s+"), " ").trim()
    private fun normalizedKey(text: String): String = text.lowercase().replace(Regex("[^0-9a-z가-힣]"), "").take(240)

    private val SECTION_PRIORITIES = setOf(
        "사업의 내용", "영업의 현황", "매출", "수주", "연구개발", "원재료", "생산", "설비", "위험", "투자"
    )
    private val CAUSAL_MARKERS = setOf(
        "원인", "영향", "증가", "감소", "확대", "축소", "상승", "하락", "개선", "악화", "부담", "회복", "투자", "증설"
    )
}

package com.krstock.v3.data.evidence

import com.krstock.v3.data.model.ContextEvidence
import com.krstock.v3.data.model.EvidenceBundle
import com.krstock.v3.data.model.EvidenceKind
import com.krstock.v3.data.model.EvidenceSourceTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object ContextEvidenceRepository {
    private const val FRONT = "https://m.stock.naver.com/front-api"
    private const val DART = "https://dart.fss.or.kr"
    private const val MAX_ITEMS_PER_KIND = 12
    private const val MAX_DART_BODY = 120_000
    private val cache = ConcurrentHashMap<String, EvidenceBundle>()

    suspend fun load(issuerId: String): EvidenceBundle {
        cache[issuerId]?.let { return it }
        val loaded = withContext(Dispatchers.IO) { fetch(issuerId) }
        cache[issuerId] = loaded
        return loaded
    }

    private fun fetch(issuerId: String): EvidenceBundle {
        var newsError: String? = null
        var naverDisclosureError: String? = null
        var dartError: String? = null

        val news = try {
            val text = get("$FRONT/news/list/integration?itemCode=$issuerId&page=1&pageSize=20")
            parse(text, EvidenceKind.NEWS)
        } catch (t: Throwable) {
            newsError = t.javaClass.simpleName
            emptyList()
        }

        val naverDisclosures = try {
            val text = get("$FRONT/stock/domestic/disclosure?code=$issuerId&page=1&pageSize=20")
            parse(text, EvidenceKind.DISCLOSURE)
        } catch (t: Throwable) {
            naverDisclosureError = t.javaClass.simpleName
            emptyList()
        }

        val dartDisclosures = try {
            fetchDartDisclosureIndex(issuerId).also {
                if (it.isEmpty()) dartError = "EMPTY"
            }
        } catch (t: Throwable) {
            dartError = t.javaClass.simpleName
            emptyList()
        }

        val mergedDisclosures = (dartDisclosures + naverDisclosures)
            .distinctBy { "${normalize(it.title)}:${it.publishedAt.take(10)}" }
            .sortedWith(
                compareBy<ContextEvidence> { it.sourceTier.priority }
                    .thenByDescending { it.publishedAt }
            )
            .take(24)

        val disclosures = hydratePeriodicDartBodies(mergedDisclosures)
        val diff = DisclosureDiffEngine.compare(disclosures)

        val errors = buildList {
            newsError?.let { add("news=$it") }
            naverDisclosureError?.let { add("naverDisclosure=$it") }
            dartError?.let { add("dart=$it") }
        }

        return EvidenceBundle(
            issuerId = issuerId,
            news = news,
            disclosures = disclosures,
            disclosureDiff = diff,
            loaded = true,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString(", ")
        )
    }

    private fun fetchDartDisclosureIndex(issuerId: String): List<ContextEvidence> {
        val html = postForm(
            "$DART/dsab001/search.ax",
            linkedMapOf(
                "textCrpNm" to issuerId,
                "currentPage" to "1",
                "maxResults" to "30",
                "maxLinks" to "10",
                "sort" to "date",
                "series" to "desc",
                "finalReport" to "recent"
            ),
            referer = "$DART/dsab001/main.do"
        )
        return parseDartSearchHtml(html)
    }

    internal fun parseDartSearchHtml(html: String): List<ContextEvidence> {
        val rows = Regex("(?is)<tr[^>]*>(.*?)</tr>").findAll(html).map { it.groupValues[1] }
        val receiptRegex = Regex("rcpNo=(\\d{12,16})", RegexOption.IGNORE_CASE)
        val results = mutableListOf<ContextEvidence>()

        rows.forEach { row ->
            val receipt = receiptRegex.find(row)?.groupValues?.getOrNull(1) ?: return@forEach
            val anchor = Regex(
                "(?is)<a[^>]*(?:href|onclick)=[\\\"'][^\\\"']*${Regex.escape(receipt)}[^\\\"']*[\\\"'][^>]*>(.*?)</a>"
            ).find(row)
            val rowText = cleanHtml(row)
            val title = anchor?.groupValues?.getOrNull(1)?.let(::cleanHtml)
                ?.takeIf { it.length >= 2 }
                ?: inferReportTitle(rowText)
            if (title.isBlank()) return@forEach

            val date = Regex("20\\d{2}[./-]?\\d{2}[./-]?\\d{2}")
                .findAll(rowText)
                .map { normalizeDate(it.value) }
                .lastOrNull()
                .orEmpty()

            results += ContextEvidence(
                id = receipt,
                kind = EvidenceKind.DISCLOSURE,
                title = title,
                source = "DART",
                publishedAt = date,
                url = "$DART/dsaf001/main.do?rcpNo=$receipt",
                sourceTier = EvidenceSourceTier.DART_PRIMARY,
                receiptNo = receipt
            )
        }

        return results
            .distinctBy { it.receiptNo }
            .sortedByDescending { it.publishedAt }
            .take(30)
    }

    private fun inferReportTitle(rowText: String): String {
        val cleaned = rowText.replace(Regex("\\s+"), " ").trim()
        val known = listOf(
            "사업보고서", "반기보고서", "분기보고서", "주요사항보고서",
            "단일판매ㆍ공급계약", "단일판매·공급계약", "유상증자", "무상증자",
            "전환사채", "자기주식", "현금ㆍ현물배당", "현금·현물배당",
            "영업(잠정)실적", "연결재무제표기준영업(잠정)실적"
        )
        val hit = known.firstOrNull { cleaned.contains(it) }
        return hit ?: cleaned.take(100)
    }

    private fun hydratePeriodicDartBodies(items: List<ContextEvidence>): List<ContextEvidence> {
        if (items.isEmpty()) return items
        val periodicIds = items
            .filter {
                it.sourceTier == EvidenceSourceTier.DART_PRIMARY &&
                    isPeriodicReport(it.title) &&
                    it.receiptNo.length >= 12
            }
            .sortedByDescending { it.publishedAt }
            .take(2)
            .map { it.id }
            .toSet()
        if (periodicIds.isEmpty()) return items

        return items.map { item ->
            if (item.id !in periodicIds) return@map item
            val body = runCatching { fetchDartPrimaryText(item.receiptNo) }.getOrDefault("")
            item.copy(bodyText = body)
        }
    }

    private fun fetchDartPrimaryText(receiptNo: String): String {
        if (!receiptNo.matches(Regex("\\d{12,16}"))) return ""
        val mainUrl = "$DART/dsaf001/main.do?rcpNo=${URLEncoder.encode(receiptNo, "UTF-8")}" 
        val mainHtml = get(mainUrl, referer = "$DART/")
        val candidates = viewerCandidates(mainHtml, receiptNo)
        if (candidates.isEmpty()) return cleanHtml(mainHtml).take(MAX_DART_BODY)

        val texts = mutableListOf<String>()
        candidates.take(2).forEach { url ->
            runCatching { get(url, referer = mainUrl) }
                .getOrNull()
                ?.let(::cleanHtml)
                ?.takeIf { it.length >= 150 }
                ?.let(texts::add)
        }
        return texts.joinToString("\n\n").take(MAX_DART_BODY)
    }

    private fun viewerCandidates(mainHtml: String, receiptNo: String): List<String> {
        data class Candidate(val score: Int, val url: String)
        val out = mutableListOf<Candidate>()
        val viewDoc = Regex(
            """viewDoc\(\s*['\"]?(\d{12,16})['\"]?\s*,\s*['\"]?(\d+)['\"]?\s*,\s*['\"]?(\d+)['\"]?\s*,\s*['\"]?(\d+)['\"]?\s*,\s*['\"]?(\d+)['\"]?\s*,\s*['\"]?([^'\")]+)['\"]?\s*\)""",
            RegexOption.IGNORE_CASE
        )
        viewDoc.findAll(mainHtml).forEach { match ->
            val rcp = match.groupValues[1].ifBlank { receiptNo }
            val dcm = match.groupValues[2]
            val ele = match.groupValues[3]
            val offset = match.groupValues[4]
            val length = match.groupValues[5]
            val dtd = match.groupValues[6]
            val start = (match.range.first - 180).coerceAtLeast(0)
            val end = (match.range.last + 180).coerceAtMost(mainHtml.lastIndex)
            val context = mainHtml.substring(start, end + 1)
            val score = sectionScore(context)
            val url = "$DART/report/viewer.do?rcpNo=$rcp&dcmNo=$dcm&eleId=$ele&offset=$offset&length=$length&dtd=${URLEncoder.encode(dtd, "UTF-8")}" 
            out += Candidate(score, url)
        }

        Regex("""(?:https?://dart\.fss\.or\.kr)?/report/viewer\.do\?[^'\"<>\s]+""", RegexOption.IGNORE_CASE)
            .findAll(mainHtml)
            .forEach { match ->
                val raw = match.value.replace("&amp;", "&")
                val url = if (raw.startsWith("http")) raw else DART + raw
                val start = (match.range.first - 180).coerceAtLeast(0)
                val end = (match.range.last + 180).coerceAtMost(mainHtml.lastIndex)
                out += Candidate(sectionScore(mainHtml.substring(start, end + 1)), url)
            }

        return out
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.url })
            .map { it.url }
            .distinct()
    }

    private fun sectionScore(context: String): Int {
        val t = cleanHtml(context).lowercase()
        val high = listOf("사업의 내용", "위험", "연구개발", "생산", "원재료", "매출", "수주", "설비", "경영진", "영업")
        return high.count { t.contains(it) } * 3
    }

    private fun get(url: String, referer: String = "https://m.stock.naver.com/"): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 6_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json,text/plain,text/html,*/*")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) KR4/2.0")
            setRequestProperty("Referer", referer)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("HTTP_$status")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun postForm(url: String, form: Map<String, String>, referer: String): String {
        val body = form.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8") }=${URLEncoder.encode(value, "UTF-8") }"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000
            readTimeout = 7_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/html,*/*")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Content-Length", bytes.size.toString())
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) KR4/2.0")
            setRequestProperty("Referer", referer)
        }
        try {
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status !in 200..299) error("HTTP_$status")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(text: String, kind: EvidenceKind): List<ContextEvidence> {
        val root = JSONTokener(text).nextValue()
        val objects = mutableListOf<JSONObject>()
        walk(root, objects)

        return objects.mapNotNull { obj -> candidate(obj, kind) }
            .filter { it.title.length >= 2 }
            .distinctBy { normalize(it.title) }
            .sortedByDescending { it.publishedAt }
            .take(MAX_ITEMS_PER_KIND)
    }

    private fun walk(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                out += node
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = node.opt(key)
                    if (child is JSONObject || child is JSONArray) walk(child, out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val child = node.opt(i)
                    if (child is JSONObject || child is JSONArray) walk(child, out)
                }
            }
        }
    }

    private fun candidate(obj: JSONObject, kind: EvidenceKind): ContextEvidence? {
        val title = first(obj, when (kind) {
            EvidenceKind.NEWS -> arrayOf("title", "articleTitle", "newsTitle", "headline", "subject")
            EvidenceKind.DISCLOSURE -> arrayOf("title", "reportName", "disclosureTitle", "reportNm", "subject", "name")
            EvidenceKind.IR, EvidenceKind.OFFICIAL -> arrayOf("title", "name", "subject")
        })?.let(::clean) ?: return null

        val published = first(obj, arrayOf(
            "datetime", "date", "publishedAt", "publishDate", "writeDate", "createdAt",
            "rceptDt", "receiptDate", "disclosureDate", "regDate", "localTradedAt"
        ))?.let(::clean).orEmpty()

        val source = first(obj, arrayOf(
            "officeName", "source", "providerName", "pressName", "companyName", "corpName", "market"
        ))?.let(::clean).orEmpty().ifBlank {
            if (kind == EvidenceKind.DISCLOSURE) "네이버 공시목록" else "뉴스"
        }

        val receiptNo = first(obj, arrayOf("receiptNo", "rceptNo", "rcept_no", "reportNo"))
            ?.filter(Char::isDigit)
            .orEmpty()

        val rawUrl = first(obj, arrayOf("url", "link", "endUrl", "detailUrl", "articleUrl"))
            ?.let(::clean)
            ?.let(::absoluteUrl)
            .orEmpty()
        val isDirectDart = kind == EvidenceKind.DISCLOSURE && receiptNo.length >= 12
        val url = if (isDirectDart) "$DART/dsaf001/main.do?rcpNo=$receiptNo" else rawUrl

        val id = buildString {
            val direct = first(obj, arrayOf("id", "articleId", "receiptNo", "rceptNo", "disclosureId", "seq"))
            if (!direct.isNullOrBlank()) append(clean(direct))
            val oid = obj.optString("oid", "")
            val aid = obj.optString("aid", "")
            if (isEmpty() && (oid.isNotBlank() || aid.isNotBlank())) append("$oid:$aid")
            if (isEmpty()) append("${kind.name}:${normalize(title).hashCode()}:$published")
        }

        val evidenceShape = published.isNotBlank() || url.isNotBlank() ||
            obj.has("oid") || obj.has("aid") || obj.has("receiptNo") || obj.has("rceptNo") ||
            obj.has("officeName") || obj.has("reportName") || obj.has("disclosureTitle")
        if (!evidenceShape) return null

        return ContextEvidence(
            id = id,
            kind = kind,
            title = title,
            source = source,
            publishedAt = normalizeDate(published),
            url = url,
            sourceTier = when {
                isDirectDart -> EvidenceSourceTier.DART_PRIMARY
                kind == EvidenceKind.IR -> EvidenceSourceTier.COMPANY_IR
                kind == EvidenceKind.OFFICIAL -> EvidenceSourceTier.COMPANY_OFFICIAL
                kind == EvidenceKind.NEWS -> EvidenceSourceTier.TRUSTED_MEDIA
                else -> EvidenceSourceTier.OTHER
            },
            receiptNo = receiptNo
        )
    }

    private fun first(obj: JSONObject, keys: Array<String>): String? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.opt(key)?.toString()?.trim().orEmpty()
            if (value.isNotBlank() && value != "null") return value
        }
        return null
    }

    private fun clean(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanHtml(value: String): String = value
        .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalize(value: String): String = clean(value).lowercase().replace(Regex("[^0-9a-z가-힣]"), "")

    private fun normalizeDate(value: String): String {
        val digits = value.filter(Char::isDigit)
        return when {
            Regex("\\d{4}-\\d{2}-\\d{2}.*").matches(value) -> value.take(16)
            digits.length >= 8 -> "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}" +
                if (digits.length >= 12) " ${digits.substring(8, 10)}:${digits.substring(10, 12)}" else ""
            else -> value
        }
    }

    private fun absoluteUrl(value: String): String = when {
        value.startsWith("https://") || value.startsWith("http://") -> value
        value.startsWith("/") -> "https://m.stock.naver.com$value"
        value.isBlank() -> ""
        else -> "https://m.stock.naver.com/$value"
    }

    private fun isPeriodicReport(title: String): Boolean {
        val t = title.replace(" ", "")
        return t.contains("사업보고서") || t.contains("반기보고서") || t.contains("분기보고서")
    }
}

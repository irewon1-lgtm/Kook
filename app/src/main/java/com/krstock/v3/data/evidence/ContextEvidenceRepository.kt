package com.krstock.v3.data.evidence

import com.krstock.v3.data.model.ContextEvidence
import com.krstock.v3.data.model.EvidenceBundle
import com.krstock.v3.data.model.EvidenceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

object ContextEvidenceRepository {
    private const val FRONT = "https://m.stock.naver.com/front-api"
    private const val NAVER_PC = "https://finance.naver.com"
    private const val DART_MAIN = "https://dart.fss.or.kr/dsaf001/main.do"
    private const val MAX_NEWS = 12
    private const val MAX_DISCLOSURES = 30
    private val cache = ConcurrentHashMap<String, EvidenceBundle>()

    suspend fun load(issuerId: String): EvidenceBundle {
        cache[issuerId]?.let { return it }
        val loaded = withContext(Dispatchers.IO) { fetch(issuerId) }
        cache[issuerId] = loaded
        return loaded
    }

    private fun fetch(issuerId: String): EvidenceBundle {
        var newsError: String? = null
        var disclosureError: String? = null
        var dartIndexError: String? = null

        val news = try {
            val text = getUtf8("$FRONT/news/list/integration?itemCode=$issuerId&page=1&pageSize=20")
            parse(text, EvidenceKind.NEWS).take(MAX_NEWS)
        } catch (t: Throwable) {
            newsError = t.javaClass.simpleName
            emptyList()
        }

        val mobileDisclosures = try {
            val text = getUtf8("$FRONT/stock/domestic/disclosure?code=$issuerId&page=1&pageSize=20")
            parse(text, EvidenceKind.DISCLOSURE)
        } catch (t: Throwable) {
            disclosureError = t.javaClass.simpleName
            emptyList()
        }

        // Naver mobile disclosure JSON currently exposes the list but not a DART
        // receipt number. The PC disclosure list still links each row to the public
        // DART receipt. Use it only as an index: the actual evidence text is fetched
        // from DART itself by DartPrimarySourceRepository.
        val dartIndexed = try {
            (1..3).flatMap { page ->
                val html = getText(
                    "$NAVER_PC/item/news_notice.naver?code=$issuerId&page=$page",
                    Charset.forName("EUC-KR"),
                    NAVER_PC
                )
                parsePcDisclosureHtml(html)
            }.distinctBy { it.receiptNo }.take(MAX_DISCLOSURES)
        } catch (t: Throwable) {
            dartIndexError = t.javaClass.simpleName
            emptyList()
        }

        val disclosures = mergeDisclosures(dartIndexed, mobileDisclosures).take(MAX_DISCLOSURES)
        if (disclosures.isEmpty() && disclosureError == null) disclosureError = "EMPTY"

        val combinedError = listOfNotNull(
            newsError?.let { "news=$it" },
            disclosureError?.let { "disclosure=$it" },
            dartIndexError?.let { "dart_index=$it" }
        ).takeIf { it.isNotEmpty() }?.joinToString(", ")

        return EvidenceBundle(
            issuerId = issuerId,
            news = news,
            disclosures = disclosures,
            loaded = true,
            error = combinedError
        )
    }

    private fun mergeDisclosures(
        dartIndexed: List<ContextEvidence>,
        mobile: List<ContextEvidence>
    ): List<ContextEvidence> {
        val result = mutableListOf<ContextEvidence>()
        result += dartIndexed
        mobile.forEach { item ->
            val match = result.indexOfFirst {
                normalize(it.title) == normalize(item.title) &&
                    (it.publishedAt.take(10) == item.publishedAt.take(10) || it.publishedAt.isBlank() || item.publishedAt.isBlank())
            }
            if (match >= 0) {
                val primary = result[match]
                result[match] = primary.copy(
                    source = if (primary.source.isBlank()) item.source else primary.source,
                    publishedAt = primary.publishedAt.ifBlank { item.publishedAt }
                )
            } else {
                result += item
            }
        }
        return result.distinctBy {
            if (it.receiptNo.isNotBlank()) "receipt:${it.receiptNo}"
            else "meta:${normalize(it.title)}:${it.publishedAt.take(10)}"
        }.sortedByDescending { it.publishedAt }
    }

    internal fun parsePcDisclosureHtml(html: String): List<ContextEvidence> {
        val rowRegex = Regex("(?is)<tr[^>]*>(.*?)</tr>")
        val linkRegex = Regex("(?is)<a[^>]+href=['\"]([^'\"]+)['\"][^>]*>(.*?)</a>")
        val receiptRegex = Regex("(?:rcpNo|rceptNo|receiptNo)=?(\\d{14})", RegexOption.IGNORE_CASE)
        val dateRegex = Regex("(20\\d{2})[.\\-/](\\d{2})[.\\-/](\\d{2})")
        val result = mutableListOf<ContextEvidence>()

        for (row in rowRegex.findAll(html)) {
            val body = row.groupValues[1]
            val receipt = receiptRegex.find(body)?.groupValues?.getOrNull(1) ?: continue
            val link = linkRegex.findAll(body).firstOrNull { receiptRegex.containsMatchIn(it.groupValues[1]) }
                ?: linkRegex.find(body)
            val title = link?.groupValues?.getOrNull(2)?.let(::stripHtml)?.takeIf { it.isNotBlank() } ?: continue
            val dateMatch = dateRegex.find(stripHtml(body))
            val date = dateMatch?.let { "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }.orEmpty()
            result += ContextEvidence(
                id = receipt,
                kind = EvidenceKind.DISCLOSURE,
                title = title,
                source = "DART 공시",
                publishedAt = date,
                url = "$DART_MAIN?rcpNo=$receipt",
                receiptNo = receipt
            )
        }
        return result.distinctBy { it.receiptNo }.sortedByDescending { it.publishedAt }
    }

    private fun getUtf8(url: String): String = getText(url, Charsets.UTF_8, "https://m.stock.naver.com/")

    private fun getText(url: String, charset: Charset, referer: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 6_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/html,application/json,text/plain,*/*")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Safari/537.36 KR4/2.0")
            setRequestProperty("Referer", referer)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("HTTP_$status")
            return connection.inputStream.bufferedReader(charset).use { it.readText() }
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
        })?.let(::clean) ?: return null

        val published = first(obj, arrayOf(
            "datetime", "date", "publishedAt", "publishDate", "writeDate", "createdAt",
            "rceptDt", "receiptDate", "disclosureDate", "regDate", "localTradedAt"
        ))?.let(::clean).orEmpty()

        val source = first(obj, arrayOf(
            "officeName", "source", "providerName", "pressName", "companyName", "corpName", "market"
        ))?.let(::clean).orEmpty().ifBlank {
            if (kind == EvidenceKind.DISCLOSURE) "공시" else "뉴스"
        }

        val rawUrl = first(obj, arrayOf("url", "link", "endUrl", "detailUrl", "articleUrl"))
            ?.let(::clean)
            .orEmpty()
        val url = rawUrl.takeIf { it.isNotBlank() }?.let(::absoluteUrl).orEmpty()

        val directReceipt = first(obj, arrayOf("receiptNo", "rceptNo", "rcept_no", "rcpNo"))
            ?.let(::clean)
            ?.let { Regex("\\d{14}").find(it)?.value }
        val receiptNo = if (kind == EvidenceKind.DISCLOSURE) {
            directReceipt ?: Regex("(?:rcpNo|rceptNo|receiptNo)=?(\\d{14})", RegexOption.IGNORE_CASE)
                .find(rawUrl)?.groupValues?.getOrNull(1)
                ?: Regex("\\b20\\d{12}\\b").find(rawUrl)?.value.orEmpty()
        } else ""

        val id = buildString {
            val direct = first(obj, arrayOf("id", "articleId", "receiptNo", "rceptNo", "rcept_no", "rcpNo", "disclosureId", "seq"))
            if (!direct.isNullOrBlank()) append(clean(direct))
            val oid = obj.optString("oid", "")
            val aid = obj.optString("aid", "")
            if (isEmpty() && (oid.isNotBlank() || aid.isNotBlank())) append("$oid:$aid")
            if (isEmpty()) append("${kind.name}:${normalize(title).hashCode()}:$published")
        }

        val evidenceShape = published.isNotBlank() || url.isNotBlank() ||
            obj.has("oid") || obj.has("aid") || obj.has("receiptNo") || obj.has("rceptNo") || obj.has("rcpNo") ||
            obj.has("officeName") || obj.has("reportName") || obj.has("disclosureTitle")
        if (!evidenceShape) return null

        return ContextEvidence(
            id = id,
            kind = kind,
            title = title,
            source = source,
            publishedAt = normalizeDate(published),
            url = url,
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

    private fun stripHtml(value: String): String = clean(
        value.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<[^>]+>"), " ")
    )

    private fun clean(value: String): String = value
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
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
}

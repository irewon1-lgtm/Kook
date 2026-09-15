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
import java.util.concurrent.ConcurrentHashMap

object ContextEvidenceRepository {
    private const val FRONT = "https://m.stock.naver.com/front-api"
    private const val MAX_ITEMS_PER_KIND = 12
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

        val news = try {
            val text = get("$FRONT/news/list/integration?itemCode=$issuerId&page=1&pageSize=20")
            parse(text, EvidenceKind.NEWS)
        } catch (t: Throwable) {
            newsError = t.javaClass.simpleName
            emptyList()
        }

        val disclosures = try {
            val text = get("$FRONT/stock/domestic/disclosure?code=$issuerId&page=1&pageSize=20")
            parse(text, EvidenceKind.DISCLOSURE)
        } catch (t: Throwable) {
            disclosureError = t.javaClass.simpleName
            emptyList()
        }

        val combinedError = when {
            newsError == null && disclosureError == null -> null
            else -> "news=${newsError ?: "OK"}, disclosure=${disclosureError ?: "OK"}"
        }
        return EvidenceBundle(
            issuerId = issuerId,
            news = news,
            disclosures = disclosures,
            loaded = true,
            error = combinedError
        )
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 6_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) KR4/1.0")
            setRequestProperty("Referer", "https://m.stock.naver.com/")
        }
        try {
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

        val url = first(obj, arrayOf("url", "link", "endUrl", "detailUrl", "articleUrl"))
            ?.let(::clean)
            ?.let(::absoluteUrl)
            .orEmpty()

        val id = buildString {
            val direct = first(obj, arrayOf("id", "articleId", "receiptNo", "rceptNo", "disclosureId", "seq"))
            if (!direct.isNullOrBlank()) append(clean(direct))
            val oid = obj.optString("oid", "")
            val aid = obj.optString("aid", "")
            if (isEmpty() && (oid.isNotBlank() || aid.isNotBlank())) append("$oid:$aid")
            if (isEmpty()) append("${kind.name}:${normalize(title).hashCode()}:$published")
        }

        // Nested metadata can contain a generic `title`; require at least one additional
        // evidence-like field so menu/header objects do not become fake news items.
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
            url = url
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

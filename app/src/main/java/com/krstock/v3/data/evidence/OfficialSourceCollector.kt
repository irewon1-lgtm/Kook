package com.krstock.v3.data.evidence

import com.krstock.v3.data.model.ContextEvidence
import com.krstock.v3.data.model.EvidenceKind
import com.krstock.v3.data.model.EvidenceSourceTier
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

internal data class OfficialSourceCollection(
    val corpCode: String = "",
    val homepage: String = "",
    val irHomepage: String = "",
    val ir: List<ContextEvidence> = emptyList(),
    val official: List<ContextEvidence> = emptyList(),
    val error: String? = null
)

/**
 * Fail-closed first-party source collector.
 *
 * Trust chain:
 * 1) DART public search resolves the DART corporation code.
 * 2) DART corporate overview resolves the company's homepage / IR homepage.
 * 3) Only those authoritative hosts (and their subdomains) may be crawled.
 * 4) External/social/ad/login links are discarded rather than reclassified as first-party.
 */
object OfficialSourceCollector {
    private const val DART = "https://dart.fss.or.kr"
    private const val MAX_PAGE_CHARS = 450_000
    private const val MAX_BODY_CHARS = 30_000
    private const val MAX_ITEMS = 12

    internal data class CompanyProfile(
        val corpCode: String,
        val homepage: String = "",
        val irHomepage: String = ""
    )

    internal data class ParsedOfficialPage(
        val evidence: List<ContextEvidence>,
        val discoveryPages: List<String>
    )

    fun collectFromDartSearchHtml(searchHtml: String): OfficialSourceCollection {
        val corpCode = parseCorpCodeFromDartSearch(searchHtml)
            ?: return OfficialSourceCollection(error = "DART_CORP_CODE_NOT_FOUND")
        val profileUrl = "$DART/dsae001/selectPopup.ax?selectKey=${URLEncoder.encode(corpCode, "UTF-8")}"
        val profileHtml = runCatching { get(profileUrl, DART) }
            .getOrElse { return OfficialSourceCollection(corpCode = corpCode, error = "DART_PROFILE_${it.javaClass.simpleName}") }
        val profile = parseCompanyProfile(corpCode, profileHtml)
        if (profile.homepage.isBlank() && profile.irHomepage.isBlank()) {
            return OfficialSourceCollection(corpCode = corpCode, error = "DART_OFFICIAL_ROOTS_EMPTY")
        }

        val irItems = mutableListOf<ContextEvidence>()
        val officialItems = mutableListOf<ContextEvidence>()
        val errors = mutableListOf<String>()

        if (profile.irHomepage.isNotBlank()) {
            runCatching { crawlRoot(profile.irHomepage, EvidenceKind.IR) }
                .onSuccess { irItems += it }
                .onFailure { errors += "ir=${it.javaClass.simpleName}" }
        }
        if (profile.homepage.isNotBlank()) {
            runCatching { crawlRoot(profile.homepage, EvidenceKind.OFFICIAL) }
                .onSuccess { rows ->
                    rows.forEach { row ->
                        if (row.kind == EvidenceKind.IR) irItems += row else officialItems += row
                    }
                }
                .onFailure { errors += "official=${it.javaClass.simpleName}" }
        }

        val ir = dedupe(irItems).take(MAX_ITEMS)
        val official = dedupe(officialItems)
            .filterNot { candidate -> ir.any { normalizeUrl(it.url) == normalizeUrl(candidate.url) } }
            .take(MAX_ITEMS)

        return OfficialSourceCollection(
            corpCode = corpCode,
            homepage = profile.homepage,
            irHomepage = profile.irHomepage,
            ir = ir,
            official = official,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString(",")
        )
    }

    internal fun parseCorpCodeFromDartSearch(html: String): String? {
        val patterns = listOf(
            Regex("openCorpInfoNew\\(\\s*['\"](\\d{8})['\"]", RegexOption.IGNORE_CASE),
            Regex("openCorpInfo\\(\\s*['\"](\\d{8})['\"]", RegexOption.IGNORE_CASE),
            Regex("selectPopup\\.ax\\?selectKey=(\\d{8})", RegexOption.IGNORE_CASE)
        )
        return patterns.asSequence()
            .mapNotNull { it.find(html)?.groupValues?.getOrNull(1) }
            .firstOrNull()
    }

    internal fun parseCompanyProfile(corpCode: String, html: String): CompanyProfile {
        var homepage = ""
        var irHomepage = ""
        Regex("(?is)<tr[^>]*>(.*?)</tr>").findAll(html).forEach { rowMatch ->
            val row = rowMatch.groupValues[1]
            val label = cleanHtml(row).replace(" ", "").lowercase()
            val links = extractRawLinks(row)
            val firstWeb = links.firstOrNull { normalizeRootUrl(it).isNotBlank() }
                ?.let(::normalizeRootUrl)
                .orEmpty()
            when {
                label.contains("ir홈페이지") || label.contains("irhome") -> if (firstWeb.isNotBlank()) irHomepage = firstWeb
                label.contains("홈페이지") || label.contains("website") -> if (firstWeb.isNotBlank()) homepage = firstWeb
            }
        }

        if (homepage.isBlank() || irHomepage.isBlank()) {
            val text = html.replace("&amp;", "&")
            val urls = Regex("https?://[^\\s'\"<>]+", RegexOption.IGNORE_CASE)
                .findAll(text)
                .map { it.value.trimEnd('.', ',', ';', ')') }
                .map(::normalizeRootUrl)
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
            if (irHomepage.isBlank()) {
                irHomepage = urls.firstOrNull { url ->
                    val s = url.lowercase()
                    s.contains("/ir") || s.contains("investor") || s.contains("invest")
                }.orEmpty()
            }
            if (homepage.isBlank()) {
                homepage = urls.firstOrNull { !it.contains("dart.fss.or.kr", ignoreCase = true) }.orEmpty()
            }
        }
        return CompanyProfile(corpCode = corpCode, homepage = homepage, irHomepage = irHomepage)
    }

    private fun crawlRoot(root: String, preferredKind: EvidenceKind): List<ContextEvidence> {
        val normalizedRoot = normalizeRootUrl(root)
        require(normalizedRoot.isNotBlank()) { "invalid official root" }
        val rootHtml = get(normalizedRoot, normalizedRoot)
        val first = parseOfficialPage(normalizedRoot, rootHtml, preferredKind)
        val all = first.evidence.toMutableList()

        first.discoveryPages.take(2).forEach { child ->
            runCatching { get(child, normalizedRoot) }
                .getOrNull()
                ?.let { html -> parseOfficialPage(child, html, preferredKind).evidence }
                ?.let(all::addAll)
        }

        val ranked = dedupe(all)
            .sortedWith(
                compareByDescending<ContextEvidence> { evidenceScore(it) }
                    .thenByDescending { it.publishedAt }
                    .thenBy { it.title }
            )
            .take(MAX_ITEMS)

        return ranked.mapIndexed { index, item ->
            if (index >= 2 || isBinaryDocument(item.url)) return@mapIndexed item
            val hydrated = runCatching {
                val html = get(item.url, normalizedRoot)
                cleanHtml(html).take(MAX_BODY_CHARS)
            }.getOrDefault("")
            if (hydrated.length >= 120) item.copy(bodyText = hydrated) else item
        }
    }

    internal fun parseOfficialPage(baseUrl: String, html: String, preferredKind: EvidenceKind): ParsedOfficialPage {
        val base = normalizeRootUrl(baseUrl)
        if (base.isBlank()) return ParsedOfficialPage(emptyList(), emptyList())
        val rootHost = hostOf(base)
        if (rootHost.isBlank()) return ParsedOfficialPage(emptyList(), emptyList())

        val evidence = mutableListOf<ContextEvidence>()
        val discovery = mutableListOf<Pair<Int, String>>()
        val anchorRegex = Regex("(?is)<a\\b([^>]*)href\\s*=\\s*['\"]([^'\"]+)['\"]([^>]*)>(.*?)</a>")
        anchorRegex.findAll(html).forEach { match ->
            val rawHref = decodeHtml(match.groupValues[2]).trim()
            val title = cleanHtml(match.groupValues[4])
            val url = resolveUrl(base, rawHref) ?: return@forEach
            if (!isAllowedFirstParty(rootHost, hostOf(url))) return@forEach
            if (isBlockedUrl(url)) return@forEach

            val combined = "$title $url".lowercase()
            val irScore = keywordScore(combined, IR_KEYWORDS)
            val officialScore = keywordScore(combined, OFFICIAL_KEYWORDS)
            val documentBonus = if (isBinaryDocument(url)) 4 else 0
            val date = inferDate("$title $url")
            val dateBonus = if (date.isNotBlank()) 2 else 0
            val score = maxOf(irScore, officialScore) + documentBonus + dateBonus

            if (!isBinaryDocument(url) && maxOf(irScore, officialScore) >= 2) {
                discovery += (maxOf(irScore, officialScore) + dateBonus) to url
            }
            if (score < 4 || title.length < 2) return@forEach

            val kind = when {
                preferredKind == EvidenceKind.IR -> EvidenceKind.IR
                irScore >= 3 && irScore >= officialScore -> EvidenceKind.IR
                else -> EvidenceKind.OFFICIAL
            }
            evidence += ContextEvidence(
                id = "${kind.name}:${normalizeUrl(url).hashCode()}",
                kind = kind,
                title = title.take(180),
                source = if (kind == EvidenceKind.IR) "회사 IR" else "회사 공식",
                publishedAt = date,
                url = url,
                sourceTier = if (kind == EvidenceKind.IR) EvidenceSourceTier.COMPANY_IR else EvidenceSourceTier.COMPANY_OFFICIAL
            )
        }

        return ParsedOfficialPage(
            evidence = dedupe(evidence).take(MAX_ITEMS * 2),
            discoveryPages = discovery
                .sortedByDescending { it.first }
                .map { it.second }
                .distinctBy(::normalizeUrl)
                .take(4)
        )
    }

    private fun evidenceScore(item: ContextEvidence): Int {
        val combined = "${item.title} ${item.url}".lowercase()
        return keywordScore(combined, if (item.kind == EvidenceKind.IR) IR_KEYWORDS else OFFICIAL_KEYWORDS) +
            if (isBinaryDocument(item.url)) 4 else 0 +
            if (item.publishedAt.isNotBlank()) 2 else 0
    }

    private fun keywordScore(text: String, keywords: List<String>): Int = keywords.sumOf { keyword ->
        when {
            text.contains(keyword) -> if (keyword.length <= 2) 1 else 2
            else -> 0
        }
    }

    private fun extractRawLinks(html: String): List<String> =
        Regex("(?is)href\\s*=\\s*['\"]([^'\"]+)['\"]")
            .findAll(html)
            .map { decodeHtml(it.groupValues[1]).trim() }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) || looksLikeDomain(it) }
            .toList()

    internal fun inferDate(text: String): String {
        val normalized = decodeHtml(text)
        val separated = Regex("(20\\d{2})[./_-](0[1-9]|1[0-2])[./_-]([0-2]\\d|3[01])")
            .find(normalized)
        if (separated != null) {
            return "${separated.groupValues[1]}-${separated.groupValues[2]}-${separated.groupValues[3]}"
        }
        val compact = Regex("(?<!\\d)(20\\d{2})(0[1-9]|1[0-2])([0-2]\\d|3[01])(?!\\d)")
            .find(normalized)
        return compact?.let { "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }.orEmpty()
    }

    private fun resolveUrl(base: String, href: String): String? {
        val h = href.trim()
        if (h.isBlank() || h.startsWith("javascript:", true) || h.startsWith("mailto:", true) ||
            h.startsWith("tel:", true) || h.startsWith("data:", true) || h.startsWith("#")) return null
        return runCatching {
            val resolved = URI(base).resolve(h).normalize()
            if (resolved.scheme !in setOf("http", "https")) return null
            if (!resolved.userInfo.isNullOrBlank()) return null
            resolved.toString().substringBefore('#')
        }.getOrNull()
    }

    private fun normalizeRootUrl(raw: String): String {
        var value = decodeHtml(raw).trim().trim('"', '\'', '(', ')')
        if (value.isBlank()) return ""
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
            if (!looksLikeDomain(value)) return ""
            value = "https://$value"
        }
        return runCatching {
            val uri = URI(value)
            require(uri.scheme in setOf("http", "https"))
            require(uri.host?.isNotBlank() == true)
            require(uri.userInfo.isNullOrBlank())
            uri.toString().trimEnd('/')
        }.getOrDefault("")
    }

    private fun looksLikeDomain(value: String): Boolean =
        value.matches(Regex("(?i)(?:www\\.)?[a-z0-9가-힣.-]+\\.[a-z가-힣]{2,}(?:/.*)?"))

    private fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }
        .getOrDefault("")

    private fun isAllowedFirstParty(rootHost: String, candidateHost: String): Boolean {
        if (rootHost.isBlank() || candidateHost.isBlank()) return false
        val root = rootHost.removePrefix("www.")
        val candidate = candidateHost.removePrefix("www.")
        return candidate == root || candidate.endsWith(".$root") || root.endsWith(".$candidate")
    }

    private fun isBlockedUrl(url: String): Boolean {
        val s = url.lowercase()
        return BLOCKED_HOST_PARTS.any { s.contains(it) } || BLOCKED_PATH_PARTS.any { s.contains(it) }
    }

    private fun isBinaryDocument(url: String): Boolean {
        val path = runCatching { URI(url).path.lowercase() }.getOrDefault("")
        return DOCUMENT_EXTENSIONS.any { path.endsWith(it) }
    }

    private fun dedupe(items: List<ContextEvidence>): List<ContextEvidence> = items
        .filter { it.url.isNotBlank() && it.title.isNotBlank() }
        .distinctBy { normalizeUrl(it.url) }

    private fun normalizeUrl(url: String): String = url.lowercase().substringBefore('#').trimEnd('/')

    private fun get(url: String, referer: String): String {
        val expectedHost = hostOf(url)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3_500
            readTimeout = 4_500
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain,*/*;q=0.5")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) KR4-OfficialSources/3.0")
            setRequestProperty("Referer", referer)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("HTTP_$status")
            val finalHost = hostOf(connection.url.toString())
            if (expectedHost != "dart.fss.or.kr" && !isAllowedFirstParty(expectedHost, finalHost)) {
                error("CROSS_DOMAIN_REDIRECT")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8192)
                val out = StringBuilder()
                while (out.length < MAX_PAGE_CHARS) {
                    val n = reader.read(buffer)
                    if (n <= 0) break
                    out.append(buffer, 0, minOf(n, MAX_PAGE_CHARS - out.length))
                }
                out.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun cleanHtml(value: String): String = decodeHtml(
        value
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<[^>]+>"), " ")
    ).replace(Regex("\\s+"), " ").trim()

    private fun decodeHtml(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private val IR_KEYWORDS = listOf(
        "investor", "investors", "invest", "ir", "투자정보", "투자자", "기업설명", "실적발표",
        "경영실적", "earnings", "results", "presentation", "financial", "재무정보", "분기실적",
        "반기실적", "연간실적", "사업보고", "ir자료", "ir 자료"
    )
    private val OFFICIAL_KEYWORDS = listOf(
        "보도자료", "뉴스룸", "newsroom", "press", "공고", "공지", "notice", "지속가능", "esg",
        "사업보고", "경영실적", "회사소식", "official"
    )
    private val DOCUMENT_EXTENSIONS = listOf(".pdf", ".ppt", ".pptx", ".xls", ".xlsx", ".doc", ".docx")
    private val BLOCKED_HOST_PARTS = listOf(
        "facebook.com", "instagram.com", "youtube.com", "youtu.be", "linkedin.com", "blog.naver.com", "twitter.com", "x.com"
    )
    private val BLOCKED_PATH_PARTS = listOf("/login", "/signin", "/signup", "/privacy", "/terms", "/cart", "/shop")
}

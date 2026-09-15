package com.krstock.v3.data.history

import android.content.Context
import com.krstock.v3.data.model.QuarterlyHistory
import com.krstock.v3.data.model.QuarterlyPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object QuarterlyHistoryRepository {
    private const val REMOTE_URL = "https://raw.githubusercontent.com/irewon1-lgtm/Kook/main/evidence/quarterly_history.json"
    private const val CACHE_FILE = "quarterly_history.json"
    private const val FRESH_MS = 12L * 60L * 60L * 1000L
    private const val MIN_FULL_ISSUERS = 2_000

    @Volatile private var cachedRoot: JSONObject? = null
    @Volatile private var cachedSource: String = ""

    suspend fun load(context: Context, issuerId: String): QuarterlyHistory = withContext(Dispatchers.IO) {
        runCatching {
            val root = loadRoot(context.applicationContext)
            parseIssuer(root, issuerId, cachedSource)
        }.getOrElse { error ->
            QuarterlyHistory(
                issuerId = issuerId,
                loaded = true,
                source = cachedSource,
                error = error.javaClass.simpleName + ":" + (error.message ?: "history load failed")
            )
        }
    }

    private fun loadRoot(context: Context): JSONObject {
        cachedRoot?.let { return it }
        synchronized(this) {
            cachedRoot?.let { return it }
            val local = File(context.filesDir, CACHE_FILE)
            if (local.isFile && System.currentTimeMillis() - local.lastModified() <= FRESH_MS) {
                parseAndValidate(local.readText(), requireFull = true)?.let {
                    cachedSource = "기기 캐시"
                    cachedRoot = it
                    return it
                }
            }

            runCatching { download(REMOTE_URL) }
                .getOrNull()
                ?.let { parseAndValidate(it, requireFull = true) }
                ?.let { root ->
                    runCatching { local.writeText(root.toString()) }
                    cachedSource = "OpenDART 8분기 원격 스냅샷"
                    cachedRoot = root
                    return root
                }

            if (local.isFile) {
                parseAndValidate(local.readText(), requireFull = true)?.let {
                    cachedSource = "기기 캐시(오프라인)"
                    cachedRoot = it
                    return it
                }
            }

            val bundled = context.assets.open("quarterly_history.json").bufferedReader().use { it.readText() }
            val root = parseAndValidate(bundled, requireFull = false)
                ?: error("bundled quarterly history invalid")
            cachedSource = "APK 번들 스냅샷"
            cachedRoot = root
            return root
        }
    }

    private fun parseAndValidate(text: String, requireFull: Boolean): JSONObject? {
        return runCatching {
            val root = JSONObject(text)
            require(root.optInt("schema_version", -1) == 1)
            val records = root.optJSONObject("records") ?: error("records missing")
            if (requireFull) require(records.length() >= MIN_FULL_ISSUERS) {
                "quarterly history universe too small: ${records.length()}"
            }
            val periods = root.optJSONArray("target_periods")
            if (requireFull) require(periods != null && periods.length() in 4..8) {
                "target periods invalid"
            }
            root
        }.getOrNull()
    }

    internal fun parseIssuer(root: JSONObject, issuerId: String, source: String = "test"): QuarterlyHistory {
        val record = root.optJSONObject("records")?.optJSONObject(issuerId)
            ?: return QuarterlyHistory(
                issuerId = issuerId,
                loaded = true,
                source = source,
                generatedAt = root.optString("generated_at_kst"),
                error = "QUARTERLY_HISTORY_NOT_AVAILABLE"
            )
        val arr = record.optJSONArray("points")
        val points = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    add(
                        QuarterlyPoint(
                            period = p.optString("period"),
                            fiscalYear = p.optInt("fiscal_year"),
                            quarter = p.optInt("quarter"),
                            revenue = p.optNullableDouble("revenue"),
                            operatingIncome = p.optNullableDouble("operating_income"),
                            operatingMargin = p.optNullableDouble("operating_margin"),
                            revenueYoY = p.optNullableDouble("revenue_yoy"),
                            revenueQoQ = p.optNullableDouble("revenue_qoq"),
                            scope = p.optString("scope"),
                            basis = p.optString("basis"),
                            sourceFile = p.optString("source_file"),
                            reason = p.optString("reason").takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }
        }.sortedWith(compareBy<QuarterlyPoint> { it.fiscalYear }.thenBy { it.quarter })
            .takeLast(8)

        return QuarterlyHistory(
            issuerId = issuerId,
            points = points,
            loaded = true,
            source = source,
            generatedAt = root.optString("generated_at_kst"),
            error = null
        )
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return value.takeIf { it.isFinite() }
    }

    private fun download(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("User-Agent", "KR4-QuarterlyHistory/1.0")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("HTTP_$status")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

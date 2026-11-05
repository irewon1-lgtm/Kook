package com.krstock.v3.data.price

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** On-demand historical prices. Never changes M01-M04 or the composite rank. */
object PriceHistoryRepository {
    private const val BASE_URL = "https://m.stock.naver.com/api/stock"
    private const val PAGE_SIZE = 200
    private const val MAX_RESPONSE_BYTES = 1024 * 1024
    private val CODE = Regex("[0-9A-Z]{6}")

    enum class Source { LIVE, CACHE, MISSING }

    data class DailyBar(
        val date: LocalDate,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long?,
    )

    data class Result(
        val bars: List<DailyBar>,
        val source: Source,
        val priceCutoffDate: LocalDate,
        val detail: String,
    )

    suspend fun load(context: Context, issuerId: String, cutoffDate: String): Result =
        withContext(Dispatchers.IO) {
            require(CODE.matches(issuerId)) { "Invalid issue code" }
            val cutoff = LocalDate.parse(cutoffDate)
            val cacheDir = File(context.applicationContext.filesDir, "price_history_v1")
            val cacheFile = File(cacheDir, "$issuerId.json")

            val cached = runCatching {
                if (!cacheFile.isFile || cacheFile.length() !in 1L..MAX_RESPONSE_BYTES.toLong()) {
                    emptyList()
                } else {
                    parseBars(cacheFile.readText(Charsets.UTF_8), cutoff)
                }
            }.getOrDefault(emptyList())

            try {
                val firstPage = parseBars(downloadPage(issuerId, 1), cutoff)
                val secondPage = parseBars(downloadPage(issuerId, 2), cutoff)
                val loaded = (firstPage + secondPage)
                    .associateBy { it.date }
                    .values
                    .sortedBy { it.date }
                require(loaded.size >= 2) { "Not enough completed daily prices" }
                runCatching {
                    cacheDir.mkdirs()
                    val tmp = File(cacheDir, "$issuerId.json.tmp")
                    tmp.writeText(serialize(loaded), Charsets.UTF_8)
                    require(tmp.length() in 1L..MAX_RESPONSE_BYTES.toLong()) { "Invalid chart cache" }
                    if (!tmp.renameTo(cacheFile)) {
                        tmp.copyTo(cacheFile, overwrite = true)
                        tmp.delete()
                    }
                }
                Result(loaded, Source.LIVE, cutoff, "네이버증권 과거 일봉")
            } catch (_: Exception) {
                if (cached.size >= 2) {
                    Result(cached, Source.CACHE, cutoff, "네트워크 연결 실패 · 저장된 차트")
                } else {
                    Result(emptyList(), Source.MISSING, cutoff, "과거 종가 자료 미확보")
                }
            }
        }

    /** Keep verified source bars; do not invent missing days or split adjustments. */
    fun parseBars(payload: String, cutoff: LocalDate): List<DailyBar> {
        val arr = JSONArray(payload)
        val parsed = ArrayList<DailyBar>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val rawDate = row.optString("localTradedAt", "").take(10)
            val date = runCatching { LocalDate.parse(rawDate) }.getOrNull() ?: continue
            if (date.isAfter(cutoff)) continue
            val close = number(row, "closePrice") ?: continue
            val open = number(row, "openPrice") ?: close
            val high = number(row, "highPrice") ?: maxOf(open, close)
            val low = number(row, "lowPrice") ?: minOf(open, close)
            if (listOf(open, high, low, close).any { !it.isFinite() || it <= 0.0 }) continue
            if (low > high || low > minOf(open, close) || high < maxOf(open, close)) continue
            val volume = number(row, "accumulatedTradingVolume")?.takeIf { it >= 0.0 }?.toLong()
            parsed += DailyBar(date, open, high, low, close, volume)
        }
        return parsed.associateBy { it.date }.values.sortedBy { it.date }
    }

    private fun serialize(bars: List<DailyBar>): String {
        val data = JSONArray()
        bars.forEach { bar ->
            data.put(
                JSONObject()
                    .put("localTradedAt", bar.date.toString())
                    .put("openPrice", bar.open)
                    .put("highPrice", bar.high)
                    .put("lowPrice", bar.low)
                    .put("closePrice", bar.close)
                    .put("accumulatedTradingVolume", bar.volume)
            )
        }
        return data.toString()
    }

    private fun number(row: JSONObject, key: String): Double? {
        if (!row.has(key) || row.isNull(key)) return null
        return row.optString(key).replace(",", "").trim().toDoubleOrNull()
    }

    private fun downloadPage(code: String, page: Int): String {
        val url = URL("$BASE_URL/$code/price?pageSize=$PAGE_SIZE&page=$page")
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 12_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", "https://m.stock.naver.com/")
            setRequestProperty("User-Agent", "KR4-Historical-Chart/1.0")
        }
        try {
            require(c.responseCode == HttpURLConnection.HTTP_OK) {
                "Price source HTTP " + c.responseCode
            }
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val count = input.read(buf)
                    if (count < 0) break
                    require(out.size() + count <= MAX_RESPONSE_BYTES) { "Chart payload too large" }
                    out.write(buf, 0, count)
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            c.disconnect()
        }
    }
}
